package ij.plugin.filter;

import gdsc.utils.ImageJHelper;
import ij.IJ;
import ij.ImagePlus;
import ij.Macro;
import ij.Prefs;
import ij.WindowManager;
import ij.gui.GenericDialog;
import ij.gui.Roi;
import ij.macro.Interpreter;
import ij.measure.ResultsTable;
import ij.plugin.frame.RoiManager;
import ij.process.ColorProcessor;
import ij.process.FloatProcessor;
import ij.process.ImageProcessor;
import ij.process.ImageStatistics;
import ij.process.ShortProcessor;
import ij.text.TextPanel;
import ij.text.TextWindow;

import java.awt.Frame;
import java.io.BufferedWriter;
import java.io.FileOutputStream;
import java.io.OutputStreamWriter;
import java.lang.reflect.Field;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;

/*----------------------------------------------------------------------------- 
 * GDSC Plugins for ImageJ
 * 
 * Copyright (C) 2015 Alex Herbert
 * Genome Damage and Stability Centre
 * University of Sussex, UK
 * 
 * This program is free software; you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation; either version 2 of the License, or
 * (at your option) any later version.
 *---------------------------------------------------------------------------*/

/**
 * Extend the ImageJ Particle Analyser to allow the particles to be obtained from an input mask with objects
 * assigned using contiguous pixels with a unique value. If blank pixels exist between two objects with the same pixel
 * value then they will be treated as separate objects.
 * <p>
 * Adds an option to select the redirection image for particle analysis. This can be none.
 * <p>
 * If the input image is a mask then the functionality is the same as the original ParticleAnalyzer class.
 * <p>
 * Note: This class used to extend the default ImageJ ParticleAnalyzer. However the Java inheritance method invocation
 * system would not call the MaskParticleAnalyzer.analyseParticle(...) method. This may be due to it being a default
 * (package) scoped method. It works on the Linux JVM but not on Windows. It would call the protected/public methods
 * that had been overridden, just not the default scope method. I have thus changed it to extend a copy of the ImageJ
 * ParticleAnalyzer. This can be updated with new version from the ImageJ source code as appropriate and default scoped
 * methods set to protected.
 */
public class MaskParticleAnalyzer extends ParticleAnalyzerCopy
{
	private static String redirectTitle = "";
	private static boolean particleSummary = false;
	private static boolean saveHistogram = false;
	private static String histogramFile = "";
	private ImagePlus restoreRedirectImp;
	private BufferedWriter out = null;
	private HashMap<Double, int[]> summaryHistogram = null;

	private boolean useGetPixelValue;
	private float[] image;
	private float value;
	private boolean noThreshold = false;
	private double dmin, dmax;

	// Methods to allow the Analyzer class package level fields to be set.
	// This is not possible on the Windows JVM but is OK on linux.
	static Field firstParticle, lastParticle;
	static
	{
		try
		{
			firstParticle = Analyzer.class.getDeclaredField("firstParticle");
			firstParticle.setAccessible(true);

			lastParticle = Analyzer.class.getDeclaredField("lastParticle");
			lastParticle.setAccessible(true);
		}
		catch (Throwable e)
		{
			// Reflection has failed
			firstParticle = lastParticle = null;
		}
	}

	static void setAnalyzerFirstParticle(int value)
	{
		//Analyzer.firstParticle = value;
		if (firstParticle != null)
		{
			try
			{
				firstParticle.set(Analyzer.class, value);
				//IJ.log("Set firstParticle to "+value);
			}
			catch (Throwable e)
			{
				// Reflection has failed
				firstParticle = null;
			}
		}
	}

	static void setAnalyzerLastParticle(int value)
	{
		//Analyzer.lastParticle = value;
		if (lastParticle != null)
		{
			try
			{
				lastParticle.set(Analyzer.class, value);
				//IJ.log("Set lastParticle to "+value);
			}
			catch (Throwable e)
			{
				// Reflection has failed
				lastParticle = null;
			}
		}
	}

	public int setup(String arg, ImagePlus imp)
	{
		int flags = FINAL_PROCESSING;
		if (imp != null)
		{
			if ("final".equals(arg))
			{
				if (noThreshold)
				{
					imp.getProcessor().resetThreshold();
					imp.setDisplayRange(dmin, dmax);
					imp.updateAndDraw();
				}
				Analyzer.setRedirectImage(restoreRedirectImp);
				close(out);
				if (particleSummary)
					createSummary();
				return DONE;
			}
			dmin = imp.getDisplayRangeMin();
			dmax = imp.getDisplayRangeMax();

			noThreshold = isNoThreshold(imp);

			// The plugin will be run on a thresholded/mask image to define particles.
			// Choose the redirect image to sample the pixels from.
			int[] idList = ImageJHelper.getIDList();
			String[] list = new String[idList.length + 1];
			list[0] = "[None]";
			int count = 1;
			for (int id : idList)
			{
				ImagePlus imp2 = WindowManager.getImage(id);
				if (imp2 == null || imp2.getWidth() != imp.getWidth() || imp2.getHeight() != imp.getHeight())
					continue;
				if (imp2.getID() == imp.getID())
					continue;
				list[count++] = imp2.getTitle();
			}
			list = Arrays.copyOf(list, count);
			GenericDialog gd = new GenericDialog("Mask Particle Analyzer...");
			gd.addMessage("Analyses objects in an image.\n \nObjects are defined with contiguous pixels of the same value.\nIgnore pixels outside any configured thresholds.");
			gd.addChoice("Redirect_image", list, redirectTitle);
			gd.addCheckbox("Particle_summary", particleSummary);
			gd.addCheckbox("Save_histogram", saveHistogram);
			if (noThreshold)
				gd.addMessage("Warning: The image is not thresholded / 8-bit binary mask.\nContinuing will use the min/max values in the image which\nmay produce many objects.");
			gd.addHelp(gdsc.help.URL.FIND_FOCI);
			gd.showDialog();
			if (gd.wasCanceled())
				return DONE;
			int index = gd.getNextChoiceIndex();
			redirectTitle = list[index];
			particleSummary = gd.getNextBoolean();
			if (particleSummary)
				summaryHistogram = new HashMap<Double, int[]>();
			saveHistogram = gd.getNextBoolean();
			if (saveHistogram)
			{
				histogramFile = ImageJHelper.getFilename("Histogram_file", histogramFile);
				if (histogramFile != null)
				{
					int i = histogramFile.lastIndexOf('.');
					if (i == -1)
						histogramFile += ".txt";
					out = createOutput(histogramFile);
					if (out == null)
						return DONE;
				}
			}
			if (Analyzer.isRedirectImage())
			{
				// Get the current redirect image using reflection since we just want to restore it
				// and do not want errors from image size mismatch in Analyzer.getRedirectImage(imp); 
				try
				{
					Field field = Analyzer.class.getDeclaredField("redirectTarget");
					field.setAccessible(true);
					int redirectTarget = (Integer) field.get(Analyzer.class);
					restoreRedirectImp = WindowManager.getImage(redirectTarget);
					//if (restoreRedirectImp != null)
					//	System.out.println("Redirect image = " + restoreRedirectImp.getTitle());
				}
				catch (Throwable e)
				{
					// Reflection has failed
				}
			}
			ImagePlus redirectImp = (index > 0) ? WindowManager.getImage(redirectTitle) : null;
			Analyzer.setRedirectImage(redirectImp);

			useGetPixelValue = imp.getProcessor() instanceof ColorProcessor;
			image = new float[imp.getWidth() * imp.getHeight()];

			if (noThreshold)
			{
				cache(imp.getProcessor());
				float min = Float.POSITIVE_INFINITY;
				float max = Float.NEGATIVE_INFINITY;
				for (int i = 1; i < image.length; i++)
				{
					if (image[i] != 0)
					{
						if (min > image[i])
							min = image[i];
						else if (max < image[i])
							max = image[i];
					}
				}
				if (min == Float.POSITIVE_INFINITY)
				{
					IJ.error("The image has no values");
					return DONE;
				}
				imp.getProcessor().setThreshold(min, max, ImageProcessor.NO_LUT_UPDATE);
			}
		}

		return super.setup(arg, imp) + flags;
	}

	private BufferedWriter createOutput(String filename)
	{
		try
		{
			FileOutputStream fos = new FileOutputStream(filename);
			BufferedWriter out = new BufferedWriter(new OutputStreamWriter(fos, "UTF-8"));
			out.write("Histogram\tParticle Value\tPixel Value\tCount");
			out.newLine();
			return out;
		}
		catch (Exception e)
		{
			IJ.error("Failed to create histogram file: " + filename);
			return null;
		}
	}

	private BufferedWriter writeHistogram(BufferedWriter out, int id, double particleValue, int[] histogram)
	{
		if (out == null || histogram == null)
			return null;

		final String prefix = String.format("%d\t%s\t", id, Double.toString(particleValue));
		for (int i = 0; i < histogram.length; i++)
		{
			if (histogram[i] == 0)
				continue;
			try
			{
				out.write(prefix);
				out.write(Integer.toString(i));
				out.write('\t');
				out.write(Integer.toString(histogram[i]));
				out.newLine();
			}
			catch (Exception e)
			{
				close(out);
				return null;
			}
		}

		return out;
	}

	private void close(BufferedWriter out)
	{
		if (out != null)
		{
			try
			{
				out.close();
			}
			catch (Exception e)
			{
			}
		}
	}

	public boolean isNoThreshold(ImagePlus imp)
	{
		boolean noThreshold = false;
		ImageProcessor ip = imp.getProcessor();
		double t1 = ip.getMinThreshold();
		int imageType;
		if (ip instanceof ShortProcessor)
			imageType = SHORT;
		else if (ip instanceof FloatProcessor)
			imageType = FLOAT;
		else
			imageType = BYTE;
		if (t1 == ImageProcessor.NO_THRESHOLD)
		{
			ImageStatistics stats = imp.getStatistics();
			if (imageType != BYTE || (stats.histogram[0] + stats.histogram[255] != stats.pixelCount))
			{
				noThreshold = true;
			}
		}
		return noThreshold;
	}

	private void cache(ImageProcessor ip)
	{
		// Cache a floating-point copy of the image
		final int w = ip.getWidth();
		final int h = ip.getHeight();
		for (int y = 0, i = 0; y < h; y++)
			for (int x = 0; x < w; x++, i++)
			{
				image[i] = (useGetPixelValue) ? ip.getPixelValue(x, y) : ip.getf(i);
			}
	}

	@Override
	public void run(ImageProcessor ip)
	{
		cache(ip);
		super.run(ip);
	}

	@Override
	protected void analyzeParticle(int x, int y, ImagePlus imp, ImageProcessor ip)
	{
		// x,y - the position the particle was first found
		// imp - the particle image
		// ip - the current processor from the particle image 

		// We need to perform the same work as the super-class but instead of outlining using the 
		// configured thresholds in the particle image we just use the position's current value.
		// Do this by zeroing all pixels that are not the same value and then calling the super-class method.
		ImageProcessor originalIp = ip.duplicate();
		value = (useGetPixelValue) ? ip.getPixelValue(x, y) : ip.getf(x, y);
		//IJ.log(String.format("Analysing x=%d,y=%d value=%f", x, y, value));
		for (int i = 0; i < image.length; i++)
			if (image[i] != value)
				ip.set(i, 0);

		ImageProcessor particleIp = ip.duplicate();
		//System.out.printf("Particle = %f\n", value);
		//ImageJHelper.display("Particle", particleIp);
		super.analyzeParticle(x, y, imp, ip);

		// At the end of processing the analyser fills the image processor to prevent 
		// re-processing this object's pixels. 
		// We must copy back the filled pixel values.
		final int newValue = ip.get(x, y);
		//System.out.printf("Particle changed to = %d\n", newValue);
		for (int i = 0; i < image.length; i++)
		{
			// Check if different from the input particle
			if (ip.get(i) != particleIp.get(i))
			{
				// Change to the reset value
				originalIp.set(i, newValue);
			}
			// Now copy back all the pixels from the original processor
			ip.set(i, originalIp.get(i));
		}
	}

	/**
	 * Saves statistics for one particle in a results table. This is
	 * a method subclasses may want to override.
	 */
	@Override
	protected void saveResults(ImageStatistics stats, Roi roi)
	{
		analyzer.saveResults(stats, roi);
		if (recordStarts)
		{
			rt.addValue("XStart", stats.xstart);
			rt.addValue("YStart", stats.ystart);
		}

		//IJ.log(String.format("Saving x=%d,y=%d count=%d, value=%f", roi.getBounds().x, roi.getBounds().y,
		//		stats.pixelCount, value));
		rt.addValue("Particle Value", value);
		rt.addValue("Pixels", stats.pixelCount);

		// Optionally save histogram to file
		int[] hist = (stats.histogram16 != null) ? stats.histogram16 : stats.histogram;
		if (hist != null)
		{
			final double particleValue = value;
			out = writeHistogram(out, rt.getCounter(), particleValue, hist);
			if (particleSummary)
			{
				// Create and store a cumulative histogram if we are summarising the particles
				if (summaryHistogram.containsKey(particleValue))
				{
					int[] hist2 = summaryHistogram.get(particleValue);
					if (hist.length < hist2.length)
					{
						int[] tmp = hist;
						hist = hist2;
						hist2 = tmp;
					}
					for (int i = 0; i < hist2.length; i++)
						hist[i] += hist2[i];
				}

				summaryHistogram.put(particleValue, hist);
			}
		}

		// Copy the superclass methods using the super-class variables obtained from relfection
		if (addToManager)
		{
			if (roiManager == null)
			{
				if (Macro.getOptions() != null && Interpreter.isBatchMode())
					roiManager = Interpreter.getBatchModeRoiManager();
				if (roiManager == null)
				{
					Frame frame = WindowManager.getFrame("ROI Manager");
					if (frame == null)
						IJ.run("ROI Manager...");
					frame = WindowManager.getFrame("ROI Manager");
					if (frame == null || !(frame instanceof RoiManager))
					{
						addToManager = false;
						return;
					}
					roiManager = (RoiManager) frame;
				}
				if (resetCounter)
					roiManager.runCommand("reset");
			}
			if (imp.getStackSize() > 1)
				roi.setPosition(imp.getCurrentSlice());
			if (lineWidth != 1)
				roi.setStrokeWidth(lineWidth);
			roiManager.add(imp, roi, rt.getCounter());
		}

		if (showResultsWindow && showResults)
			rt.addResults();
	}

	private void createSummary()
	{
		int nRows = rt.getCounter();
		String label = (nRows > 0) ? rt.getLabel(0) : null;

		// The second last column is the particle value
		// The last column is the number of pixels
		double[] particles = rt.getColumnAsDoubles(rt.getLastColumn() - 1);
		double[] nPixels = rt.getColumnAsDoubles(rt.getLastColumn());

		// Summarise only certain columns:
		int[] toProcess = new int[] { ResultsTable.AREA, ResultsTable.MEAN, ResultsTable.MIN, ResultsTable.MAX,
				ResultsTable.X_CENTER_OF_MASS, ResultsTable.Y_CENTER_OF_MASS, ResultsTable.INTEGRATED_DENSITY,
				ResultsTable.RAW_INTEGRATED_DENSITY };
		int next = 0;

		double[][] values = new double[toProcess.length][];

		for (int i = 0; i < rt.getLastColumn(); i++)
		{
			if (toProcess[next] != i)
				continue;

			if (rt.columnExists(i))
			{
				values[next] = rt.getColumnAsDoubles(i);
			}

			if (++next == toProcess.length)
				break;
		}

		// Map all particles to a single result
		HashMap<Double, double[]> map = new HashMap<Double, double[]>();
		LinkedList<Double> order = new LinkedList<Double>();

		// Now summarise
		for (int r = 0; r < nRows; r++)
		{
			double particle = particles[r];
			double n = nPixels[r];

			// Get the data to be summarised
			double[] data = new double[toProcess.length + 2];

			// AREA => sum this 
			if (values[0] != null)
				data[0] = values[0][r];
			// MEAN => multiply by nPixels and sum, divide at end by nPixels
			if (values[1] != null)
				data[1] = values[1][r] * n;
			// MIN => Find min
			if (values[2] != null)
				data[2] = values[2][r];
			// MAX => Find max
			if (values[3] != null)
				data[3] = values[3][r];
			// X_CENTER_OF_MASS => multiply by nPixels and sum 
			if (values[4] != null)
				data[4] = values[4][r] * n;
			// Y_CENTER_OF_MASS => multiply by nPixels and sum, divide at end by nPixels
			if (values[5] != null)
				data[5] = values[5][r] * n;
			// INTEGRATED_DENSITY == area*mean => Just compute at end

			// RAW_INTEGRATED_DENSITY == sum of pixels => sum
			if (values[7] != null)
				data[7] = values[7][r];

			data[8] = n;
			data[9] = 1;

			// Find the record for the summary
			if (map.containsKey(particle))
			{
				double[] record = map.get(particle);
				// AREA => sum this 
				record[0] += data[0];
				// MEAN => multiply by nPixels and sum, divide at end by nPixels
				record[1] += data[1];
				// MIN => Find min
				record[2] = Math.min(data[2], record[2]);
				// MAX => Find max
				record[3] = Math.max(data[3], record[3]);
				// X_CENTER_OF_MASS => multiply by nPixels and sum, divide at end by nPixels
				record[4] += data[4];
				// Y_CENTER_OF_MASS => multiply by nPixels and sum, divide at end by nPixels
				record[5] += data[5];
				// INTEGRATED_DENSITY == area*mean => Just compute at end

				// RAW_INTEGRATED_DENSITY == sum of pixels => sum
				record[7] += data[7];
				// nPixels
				record[8] += data[8];
				// nParticles
				record[9] += data[9];
			}
			else
			{
				map.put(particle, data);
				order.add(particle);
			}
		}

		// Produce summary
		ResultsTable summary = new ResultsTable();
		if (summary.getColumnHeading(ResultsTable.LAST_HEADING) == null)
			summary.setDefaultHeadings();
		for (Double particle : order)
		{
			summary.incrementCounter();
			if (label != null)
				summary.addLabel(label);

			double[] data = map.get(particle);
			double n = data[8];
			// AREA => sum this 
			if (values[0] != null)
				summary.addValue(ResultsTable.AREA, data[0]);
			// MEAN => multiply by nPixels and sum, divide at end by nPixels
			if (values[1] != null)
				summary.addValue(ResultsTable.MEAN, data[1] /= n);
			// MIN => Find min
			if (values[2] != null)
				summary.addValue(ResultsTable.MIN, data[2]);
			// MAX => Find max
			if (values[3] != null)
				summary.addValue(ResultsTable.MAX, data[3]);
			// X_CENTER_OF_MASS => multiply by nPixels and sum, divide at end by nPixels 
			if (values[4] != null)
				summary.addValue(ResultsTable.X_CENTER_OF_MASS, data[4] / n);
			// Y_CENTER_OF_MASS => multiply by nPixels and sum, divide at end by nPixels
			if (values[5] != null)
				summary.addValue(ResultsTable.Y_CENTER_OF_MASS, data[5] / n);
			// INTEGRATED_DENSITY == area*mean => Just compute at end
			if (values[6] != null) // Assumes that data[0] and data[1] were also present
				summary.addValue(ResultsTable.INTEGRATED_DENSITY, data[0] * data[1]);
			// RAW_INTEGRATED_DENSITY == sum of pixels => sum
			if (values[7] != null)
				summary.addValue(ResultsTable.RAW_INTEGRATED_DENSITY, data[7]);

			summary.addValue("Particle Value", particle.doubleValue());
			summary.addValue("Pixels", data[8]);
			summary.addValue("Particles", data[9]);
		}

		String windowTitle = "Particle Summary";

		// This method does not work on my JRE as closing a results window throws an exception
		// leaving the frame still in memory but not visible
		//summary.show(windowTitle);

		TextWindow win = null;
		String tableHeadings = summary.getColumnHeadings();
		boolean newWindow = false;

		// This method does not check the frame is visible
		//Frame frame = WindowManager.getFrame(windowTitle);

		// Find the results table if visible
		for (Frame frame : WindowManager.getNonImageWindows())
		{
			if (frame != null && frame instanceof TextWindow && frame.isVisible())
			{
				if (windowTitle.equals(frame.getTitle()))
				{
					win = (TextWindow) frame;
					break;
				}
			}
		}
		if (win == null)
		{
			// Create a new window matching the size of the "Results" table
			int w = (int) Prefs.get(TextWindow.WIDTH_KEY, 800);
			int h = (int) Prefs.get(TextWindow.HEIGHT_KEY, 250);
			win = new TextWindow(windowTitle, tableHeadings, "", w, h);
			newWindow = true;
		}
		TextPanel tp = win.getTextPanel();
		if (!newWindow)
			// Setting columns headings forces the table to be reset
			tp.setColumnHeadings(tableHeadings);
		tp.setResultsTable(summary);
		int n = summary.getCounter();
		if (n > 0)
		{
			if (tp.getLineCount() > 0)
				tp.clear();
			StringBuilder sb = new StringBuilder(n * tableHeadings.length());
			for (int i = 0; i < n; i++)
				sb.append(summary.getRowAsString(i)).append("\n");
			// Adding all the data in one go does not auto-adjust column width
			tp.append(sb.toString());
		}
		// Forces auto column width calculation
		tp.scrollToTop();
		
		// Optionally save summary histogram to file
		if (saveHistogram)
			saveSummaryHistogram(order);
	}

	private void saveSummaryHistogram(List<Double> order)
	{
		if (summaryHistogram.isEmpty())
			return;
		String summaryFilename = createSummaryFilename(histogramFile);
		BufferedWriter out = createOutput(summaryFilename);
		int id = 1;
		for (Double value : order)
		{
			out = writeHistogram(out, id++, value, summaryHistogram.get(value));
		}
		close(out);
	}

	private String createSummaryFilename(String filename)
	{
		// The histogramFile had a default .txt, so look for the extension and insert 'summary'
		int i = filename.lastIndexOf('.');
		return filename.substring(0, i) + ".summary" + filename.substring(i);
	}
}
