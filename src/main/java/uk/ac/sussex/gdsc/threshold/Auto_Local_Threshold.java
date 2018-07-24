/*-
 * #%L
 * Genome Damage and Stability Centre ImageJ Plugins
 *
 * Software for microscopy image analysis
 * %%
 * Copyright (C) 2011 - 2018 Alex Herbert
 * %%
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as
 * published by the Free Software Foundation, either version 3 of the
 * License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public
 * License along with this program.  If not, see
 * <http://www.gnu.org/licenses/gpl-3.0.html>.
 * #L%
 */
package uk.ac.sussex.gdsc.threshold;

import ij.IJ;
import ij.ImagePlus;
import ij.ImageStack;
import ij.Undo;
import ij.gui.GenericDialog;
import ij.gui.NewImage;
import ij.gui.YesNoCancelDialog;
import ij.plugin.CanvasResizer;
import ij.plugin.MontageMaker;
import ij.plugin.PlugIn;
import ij.plugin.filter.RankFilters;
import ij.process.Blitter;
import ij.process.ImageConverter;
import ij.process.ImageProcessor;
import uk.ac.sussex.gdsc.UsageTracker;

// AutoLocalThreshold segmentation
// Following the guidelines at http://pacific.mpi-cbg.de/wiki/index.php/PlugIn_Design_Guidelines
// ImageJ plugin by G. Landini at bham. ac. uk
// 1.0  15/Apr/2009
// 1.1  01/Jun/2009
// 1.2  25/May/2010

/**
 * AutoLocalThreshold segmentation plugin.
 * <p>
 * Adapted from the ImageJ plugin by G. Landini at bham. ac. uk.
 */
public class Auto_Local_Threshold implements PlugIn
{
	private static final String TITLE = "Auto Local Threshold";

	/** Ask for parameters and then execute. */
	@Override
	public void run(String arg)
	{
		UsageTracker.recordPlugin(this.getClass(), arg);

		// 1 - Obtain the currently active image:
		final ImagePlus imp = IJ.getImage();

		if (null == imp)
		{
			IJ.showMessage("There must be at least one image open");
			return;
		}

		if (imp.getBitDepth() != 8)
		{
			IJ.showMessage("Error", "Only 8-bit images are supported");
			return;
		}

		// 2 - Ask for parameters:
		final GenericDialog gd = new GenericDialog(TITLE);
		final String[] methods = { "Try all", "Bernsen", "Mean", "Median", "MidGrey", "Niblack", "Sauvola" };
		gd.addMessage("Auto Local Threshold v1.2");
		gd.addChoice("Method", methods, methods[0]);
		gd.addNumericField("Radius", 15, 0);
		gd.addMessage("Special parameters (if different from default)");
		gd.addNumericField("Parameter_1", 0, 0);
		gd.addNumericField("Parameter_2", 0, 0);
		gd.addCheckbox("White objects on black background", true);
		if (imp.getStackSize() > 1)
			gd.addCheckbox("Stack", false);
		gd.addMessage("Thresholded result is always shown in white [255].");
		gd.showDialog();
		if (gd.wasCanceled())
			return;

		// 3 - Retrieve parameters from the dialog
		final String myMethod = gd.getNextChoice();
		final int radius = (int) gd.getNextNumber();
		final double par1 = gd.getNextNumber();
		final double par2 = gd.getNextNumber();
		final boolean doIwhite = gd.getNextBoolean();
		boolean doIstack = false;

		final int stackSize = imp.getStackSize();
		if (stackSize > 1)
			doIstack = gd.getNextBoolean();

		// 4 - Execute!
		//long start = System.currentTimeMillis();
		if (myMethod.equals("Try all"))
		{
			ImageProcessor ip = imp.getProcessor();
			final int xe = ip.getWidth();
			final int ye = ip.getHeight();
			final int ml = methods.length;
			ImagePlus imp2, imp3;
			ImageStack tstack = null, stackNew;
			if (stackSize > 1 && doIstack)
			{
				if (stackSize > 25)
				{
					final YesNoCancelDialog d = new YesNoCancelDialog(IJ.getInstance(), "Auto Local Threshold",
							"You might run out of memory.\n \nDisplay " + stackSize +
									" slices?\n \n \'No\' will process without display and\noutput results to the log window.");
					if (!d.yesPressed())
					{
						//						doIlog=true; //will show in the log window
					}
					if (d.cancelPressed())
						return;
				}

				for (int j = 1; j <= stackSize; j++)
				{
					imp.setSlice(j);
					ip = imp.getProcessor();
					tstack = new ImageStack(xe, ye);
					for (int k = 1; k < ml; k++)
						tstack.addSlice(methods[k], ip.duplicate());
					imp2 = new ImagePlus("Auto Threshold", tstack);
					imp2.updateAndDraw();

					for (int k = 1; k < ml; k++)
					{
						imp2.setSlice(k);
						exec(imp2, methods[k], radius, par1, par2, doIwhite);
					}
					//if (doItAnyway){
					final CanvasResizer cr = new CanvasResizer();
					stackNew = cr.expandStack(tstack, (xe + 2), (ye + 18), 1, 1);
					imp3 = new ImagePlus("Auto Threshold", stackNew);
					imp3.updateAndDraw();
					final MontageMaker mm = new MontageMaker();
					mm.makeMontage(imp3, 3, 2, 1.0, 1, (ml - 1), 1, 0, true); // 5 columns and 3 rows
				}
				imp.setSlice(1);
				//if (doItAnyway)
				IJ.run("Images to Stack", "method=[Copy (center)] title=Montage");
				return;
			}
			//single image try all
			tstack = new ImageStack(xe, ye);
			for (int k = 1; k < ml; k++)
				tstack.addSlice(methods[k], ip.duplicate());
			imp2 = new ImagePlus("Auto Threshold", tstack);
			imp2.updateAndDraw();

			for (int k = 1; k < ml; k++)
			{
				imp2.setSlice(k);
				//IJ.log("analyzing slice with "+methods[k]);
				exec(imp2, methods[k], radius, par1, par2, doIwhite);
			}
			//imp2.setSlice(1);
			final CanvasResizer cr = new CanvasResizer();
			stackNew = cr.expandStack(tstack, (xe + 2), (ye + 18), 1, 1);
			imp3 = new ImagePlus("Auto Threshold", stackNew);
			imp3.updateAndDraw();
			final MontageMaker mm = new MontageMaker();
			mm.makeMontage(imp3, 3, 2, 1.0, 1, (ml - 1), 1, 0, true);
			return;
		}
		else if (stackSize > 1 && doIstack)
		{ //whole stack
		  //				if (doIstackHistogram) {// one global histogram
		  //					Object[] result = exec(imp, myMethod, noWhite, noBlack, doIwhite, doIset, doIlog, doIstackHistogram );
		  //				}
		  //				else{ // slice by slice
			for (int k = 1; k <= stackSize; k++)
			{
				imp.setSlice(k);
				exec(imp, myMethod, radius, par1, par2, doIwhite);
			}
			//				}
			imp.setSlice(1);
		}
		else
			exec(imp, myMethod, radius, par1, par2, doIwhite);
	}
	//IJ.showStatus(IJ.d2s((System.currentTimeMillis()-start)/1000.0, 2)+" seconds");

	/**
	 * Execute the plugin functionality: duplicate and scale the given image.
	 *
	 * @param imp
	 *            the image
	 * @param myMethod
	 *            the threshold method
	 * @param radius
	 *            the radius
	 * @param par1
	 *            the parameter 1
	 * @param par2
	 *            the parameter 2
	 * @param doIwhite
	 *            flag to set the foreground as white
	 * @return an Object[] array with the name and the scaled ImagePlus.
	 *         Does NOT show the new, image; just returns it.
	 */
	public Object[] exec(ImagePlus imp, String myMethod, int radius, double par1, double par2, boolean doIwhite)
	{
		// 0 - Check validity of parameters
		if (null == imp)
			return null;
		final ImageProcessor ip = imp.getProcessor();

		ip.getHistogram();

		IJ.showStatus("Thresholding...");

		//1 Do it
		if (imp.getStackSize() == 1)
		{
			ip.snapshot();
			Undo.setup(Undo.FILTER, imp);
		}
		// Apply the selected algorithm
		if (myMethod.equals("Bernsen"))
			Bernsen(imp, radius, par1, doIwhite);
		else if (myMethod.equals("Mean"))
			Mean(imp, radius, par1, doIwhite);
		else if (myMethod.equals("Median"))
			Median(imp, radius, par1, doIwhite);
		else if (myMethod.equals("MidGrey"))
			MidGrey(imp, radius, par1, doIwhite);
		else if (myMethod.equals("Niblack"))
			Niblack(imp, radius, par1, doIwhite);
		else if (myMethod.equals("Sauvola"))
			Sauvola(imp, radius, par1, par2, doIwhite);
		//IJ.showProgress((double)(255-i)/255);
		imp.updateAndDraw();
		imp.getProcessor().setThreshold(255, 255, ImageProcessor.NO_LUT_UPDATE);
		// 2 - Return the threshold and the image
		return new Object[] { imp };
	}

	private static void Bernsen(ImagePlus imp, int radius, double par1, boolean doIwhite)
	{
		// Bernsen recommends WIN_SIZE = 31 and CONTRAST_THRESHOLD = 15.
		//  1) Bernsen J. (1986) "Dynamic Thresholding of Grey-Level Images"
		//    Proc. of the 8th Int. Conf. on Pattern Recognition, pp. 1251-1255
		//  2) Sezgin M. and Sankur B. (2004) "Survey over Image Thresholding
		//   Techniques and Quantitative Performance Evaluation" Journal of
		//   Electronic Imaging, 13(1): 146-165
		//  http://citeseer.ist.psu.edu/sezgin04survey.html
		// Ported to ImageJ plugin from E Celebi's fourier_0.8 routines
		// This version uses a circular local window, instead of a rectagular one
		ImagePlus Maximp, Minimp;
		final ImageProcessor ip = imp.getProcessor();
		ImageProcessor ipMax, ipMin;
		int contrast_threshold = 15;
		int local_contrast;
		int mid_gray;
		byte object;
		byte backg;
		int temp;

		if (par1 != 0)
		{
			IJ.log("Bernsen: changed contrast_threshold from :" + contrast_threshold + "  to:" + par1);
			contrast_threshold = (int) par1;
		}

		if (doIwhite)
		{
			object = (byte) 0xff;
			backg = (byte) 0;
		}
		else
		{
			object = (byte) 0;
			backg = (byte) 0xff;
		}

		Maximp = duplicateImage(ip);
		ipMax = Maximp.getProcessor();
		final RankFilters rf = new RankFilters();
		rf.rank(ipMax, radius, RankFilters.MAX);// Maximum
		//Maximp.show();
		Minimp = duplicateImage(ip);
		ipMin = Minimp.getProcessor();
		rf.rank(ipMin, radius, RankFilters.MIN); //Minimum
		//Minimp.show();
		final byte[] pixels = (byte[]) ip.getPixels();
		final byte[] max = (byte[]) ipMax.getPixels();
		final byte[] min = (byte[]) ipMin.getPixels();

		for (int i = 0; i < pixels.length; i++)
		{
			local_contrast = (max[i] & 0xff) - (min[i] & 0xff);
			mid_gray = ((min[i] & 0xff) + (max[i] & 0xff)) / 2;
			temp = pixels[i] & 0x0000ff;
			if (local_contrast < contrast_threshold)
				pixels[i] = (mid_gray >= 128) ? object : backg; //Low contrast region
			else
				pixels[i] = (temp >= mid_gray) ? object : backg;
		}
		//imp.updateAndDraw();
		return;
	}

	private static void Mean(ImagePlus imp, int radius, double par1, boolean doIwhite)
	{
		// See: Image Processing Learning Resourches HIPR2
		// http://homepages.inf.ed.ac.uk/rbf/HIPR2/adpthrsh.htm
		ImagePlus Meanimp;
		final ImageProcessor ip = imp.getProcessor();
		ImageProcessor ipMean;
		int c_value = 0;
		byte object;
		byte backg;

		if (par1 != 0)
		{
			IJ.log("Mean: changed c_value from :" + c_value + "  to:" + par1);
			c_value = (int) par1;
		}

		if (doIwhite)
		{
			object = (byte) 0xff;
			backg = (byte) 0;
		}
		else
		{
			object = (byte) 0;
			backg = (byte) 0xff;
		}

		Meanimp = duplicateImage(ip);
		final ImageConverter ic = new ImageConverter(Meanimp);
		ic.convertToGray32();

		ipMean = Meanimp.getProcessor();
		final RankFilters rf = new RankFilters();
		rf.rank(ipMean, radius, RankFilters.MEAN);// Mean
		//Meanimp.show();
		final byte[] pixels = (byte[]) ip.getPixels();
		final float[] mean = (float[]) ipMean.getPixels();

		for (int i = 0; i < pixels.length; i++)
			pixels[i] = ((pixels[i] & 0xff) > (int) (mean[i] - c_value)) ? object : backg;
		//imp.updateAndDraw();
		return;
	}

	private static void Median(ImagePlus imp, int radius, double par1, boolean doIwhite)
	{
		// See: Image Processing Learning Resourches HIPR2
		// http://homepages.inf.ed.ac.uk/rbf/HIPR2/adpthrsh.htm
		ImagePlus Medianimp;
		final ImageProcessor ip = imp.getProcessor();
		ImageProcessor ipMedian;
		int c_value = 0;
		byte object;
		byte backg;

		if (par1 != 0)
		{
			IJ.log("Median: changed c_value from :" + c_value + "  to:" + par1);
			c_value = (int) par1;
		}

		if (doIwhite)
		{
			object = (byte) 0xff;
			backg = (byte) 0;
		}
		else
		{
			object = (byte) 0;
			backg = (byte) 0xff;
		}

		Medianimp = duplicateImage(ip);
		ipMedian = Medianimp.getProcessor();
		final RankFilters rf = new RankFilters();
		rf.rank(ipMedian, radius, RankFilters.MEDIAN);
		//Medianimp.show();
		final byte[] pixels = (byte[]) ip.getPixels();
		final byte[] median = (byte[]) ipMedian.getPixels();

		for (int i = 0; i < pixels.length; i++)
			pixels[i] = ((pixels[i] & 0xff) > (median[i] & 0xff) - c_value) ? object : backg;
		//imp.updateAndDraw();
		return;
	}

	private static void MidGrey(ImagePlus imp, int radius, double par1, boolean doIwhite)
	{
		// See: Image Processing Learning Resourches HIPR2
		// http://homepages.inf.ed.ac.uk/rbf/HIPR2/adpthrsh.htm
		ImagePlus Maximp, Minimp;
		final ImageProcessor ip = imp.getProcessor();
		ImageProcessor ipMax, ipMin;
		int c_value = 0;
		byte object;
		byte backg;

		if (par1 != 0)
		{
			IJ.log("MidGrey: changed c_value from :" + c_value + "  to:" + par1);
			c_value = (int) par1;
		}

		if (doIwhite)
		{
			object = (byte) 0xff;
			backg = (byte) 0;
		}
		else
		{
			object = (byte) 0;
			backg = (byte) 0xff;
		}

		Maximp = duplicateImage(ip);
		ipMax = Maximp.getProcessor();
		final RankFilters rf = new RankFilters();
		rf.rank(ipMax, radius, RankFilters.MAX);// Maximum
		//Maximp.show();
		Minimp = duplicateImage(ip);
		ipMin = Minimp.getProcessor();
		rf.rank(ipMin, radius, RankFilters.MIN); //Minimum
		//Minimp.show();
		final byte[] pixels = (byte[]) ip.getPixels();
		final byte[] max = (byte[]) ipMax.getPixels();
		final byte[] min = (byte[]) ipMin.getPixels();

		for (int i = 0; i < pixels.length; i++)
			pixels[i] = ((pixels[i] & 0xff) > ((max[i] & 0xff) + (min[i] & 0xff)) / 2 + c_value) ? object : backg;
		//imp.updateAndDraw();
		return;
	}

	private static void Niblack(ImagePlus imp, int radius, double par1, boolean doIwhite)
	{
		// Niblack recommends K_VALUE = -0.2 for images with black foreground
		// objects, and K_VALUE = +0.2 for images with white foreground objects.
		//  Niblack W. (1986) "An introduction to Digital Image Processing" Prentice-Hall.
		// Ported to ImageJ plugin from E Celebi's fourier_0.8 routines
		// This version uses a circular local window, instead of a rectagular one

		ImagePlus Meanimp, Varimp;
		final ImageProcessor ip = imp.getProcessor();
		ImageProcessor ipMean, ipVar;
		double k_value;
		byte object;
		byte backg;

		if (doIwhite)
		{
			k_value = 0.2;
			object = (byte) 0xff;
			backg = (byte) 0;
		}
		else
		{
			k_value = -0.2;
			object = (byte) 0;
			backg = (byte) 0xff;
		}

		if (par1 != 0)
		{
			IJ.log("Niblack: changed k_value from :" + k_value + "  to:" + par1);
			k_value = par1;
		}

		Meanimp = duplicateImage(ip);
		ImageConverter ic = new ImageConverter(Meanimp);
		ic.convertToGray32();

		ipMean = Meanimp.getProcessor();
		final RankFilters rf = new RankFilters();
		rf.rank(ipMean, radius, RankFilters.MEAN);// Mean
		//Meanimp.show();
		Varimp = duplicateImage(ip);
		ic = new ImageConverter(Varimp);
		ic.convertToGray32();
		ipVar = Varimp.getProcessor();
		rf.rank(ipVar, radius, RankFilters.VARIANCE); //Variance
		//Varimp.show();
		final byte[] pixels = (byte[]) ip.getPixels();
		final float[] mean = (float[]) ipMean.getPixels();
		final float[] var = (float[]) ipVar.getPixels();

		for (int i = 0; i < pixels.length; i++)
			pixels[i] = ((pixels[i] & 0xff) > (int) (mean[i] + k_value * Math.sqrt(var[i]))) ? object : backg;
		//imp.updateAndDraw();
		return;
	}

	private static void Sauvola(ImagePlus imp, int radius, double par1, double par2, boolean doIwhite)
	{
		// Sauvola recommends K_VALUE = 0.5 and R_VALUE = 128.
		// This is a modification of Niblack's thresholding method.
		// Sauvola J. and Pietaksinen M. (2000) "Adaptive Document Image Binarization"
		// Pattern Recognition, 33(2): 225-236
		// http://www.ee.oulu.fi/mvg/publications/show_pdf.php?ID=24
		// Ported to ImageJ plugin from E Celebi's fourier_0.8 routines
		// This version uses a circular local window, instead of a rectagular one

		ImagePlus Meanimp, Varimp;
		final ImageProcessor ip = imp.getProcessor();
		ImageProcessor ipMean, ipVar;
		double k_value = 0.5;
		double r_value = 128;
		byte object;
		byte backg;

		if (par1 != 0)
		{
			IJ.log("Sauvola: changed k_value from :" + k_value + "  to:" + par1);
			k_value = par1;
		}

		if (par2 != 0)
		{
			IJ.log("Sauvola: changed r_value from :" + r_value + "  to:" + par2);
			r_value = par2;
		}

		if (doIwhite)
		{
			object = (byte) 0xff;
			backg = (byte) 0;
		}
		else
		{
			object = (byte) 0;
			backg = (byte) 0xff;
		}

		Meanimp = duplicateImage(ip);
		ImageConverter ic = new ImageConverter(Meanimp);
		ic.convertToGray32();

		ipMean = Meanimp.getProcessor();
		final RankFilters rf = new RankFilters();
		rf.rank(ipMean, radius, RankFilters.MEAN);// Mean
		//Meanimp.show();
		Varimp = duplicateImage(ip);
		ic = new ImageConverter(Varimp);
		ic.convertToGray32();
		ipVar = Varimp.getProcessor();
		rf.rank(ipVar, radius, RankFilters.VARIANCE); //Variance
		//Varimp.show();
		final byte[] pixels = (byte[]) ip.getPixels();
		final float[] mean = (float[]) ipMean.getPixels();
		final float[] var = (float[]) ipVar.getPixels();

		for (int i = 0; i < pixels.length; i++)
			pixels[i] = ((pixels[i] & 0xff) > (int) (mean[i] * (1.0 + k_value * ((Math.sqrt(var[i]) / r_value) - 1.0))))
					? object
					: backg;
		//imp.updateAndDraw();
		return;
	}

	private static ImagePlus duplicateImage(ImageProcessor iProcessor)
	{
		final int w = iProcessor.getWidth();
		final int h = iProcessor.getHeight();
		final ImagePlus iPlus = NewImage.createByteImage("Image", w, h, 1, NewImage.FILL_BLACK);
		final ImageProcessor imageProcessor = iPlus.getProcessor();
		imageProcessor.copyBits(iProcessor, 0, 0, Blitter.COPY);
		return iPlus;
	}

}