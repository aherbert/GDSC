package gdsc.foci;

import gdsc.UsageTracker;

/*----------------------------------------------------------------------------- 
 * GDSC Plugins for ImageJ
 * 
 * Copyright (C) 2011 Alex Herbert
 * Genome Damage and Stability Centre
 * University of Sussex, UK
 * 
 * This program is free software; you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation; either version 2 of the License, or
 * (at your option) any later version.
 *---------------------------------------------------------------------------*/

import gdsc.core.ij.Utils;
import gdsc.core.match.Coordinate;
import gdsc.core.match.MatchCalculator;
import gdsc.core.match.MatchResult;
import gdsc.core.match.PointPair;
import ij.IJ;
import ij.ImagePlus;
import ij.ImageStack;
import ij.WindowManager;
import ij.gui.GenericDialog;
import ij.gui.Overlay;
import ij.gui.PointRoi;
import ij.io.OpenDialog;
import ij.plugin.PlugIn;
import ij.process.ImageProcessor;
import ij.text.TextWindow;

import java.awt.Color;
import java.awt.Point;
import java.awt.TextField;
import java.awt.event.MouseEvent;
import java.awt.event.MouseListener;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

/**
 * Compares the coordinates in two files and computes the match statistics.
 * <p>
 * Can read QuickPALM xls files; STORMJ xls files; and a generic CSV file.
 * <p>
 * The generic CSV file has records of the following:<br/>
 * ID,T,X,Y,Z,Value<br/>
 * Z and Value can be missing. The generic file can also be tab delimited.
 */
public class FileMatchCalculator implements PlugIn, MouseListener
{
	public class IdTimeValuedPoint extends TimeValuedPoint
	{
		public int id;

		public IdTimeValuedPoint(int id, TimeValuedPoint p)
		{
			super(p.getX(), p.getY(), p.getZ(), p.time, p.value);
			this.id = id;
		}
	}

	private static String TITLE = "Match Calculator";

	private static String title1 = "";
	private static String title2 = "";
	private static double dThreshold = 1;
	private static String mask = "";
	private static double beta = 4;
	private static boolean showPairs = false;
	private static boolean savePairs = false;
	private static boolean savePairsSingleFile = false;
	private static String filename = "";
	private static String filenameSingle = "";
	private static String image1 = "";
	private static String image2 = "";
	private static boolean showComposite = false;
	private static boolean ignoreFrames = false;
	private static boolean useSlicePosition = false;

	private String myMask, myImage1, myImage2;

	private static boolean writeHeader = true;
	private static TextWindow resultsWindow = null;
	private static TextWindow pairsWindow = null;

	private TextField text1;
	private TextField text2;

	// flag indicating the pairs have values that should be output
	private boolean valued = false;

	/*
	 * (non-Javadoc)
	 * 
	 * @see ij.plugin.PlugIn#run(java.lang.String)
	 */
	public void run(String arg)
	{
		UsageTracker.recordPlugin(this.getClass(), arg);

		if (!showDialog())
		{
			return;
		}

		TimeValuedPoint[] actualPoints = null;
		TimeValuedPoint[] predictedPoints = null;
		double d = 0;

		try
		{
			actualPoints = TimeValuePointManager.loadPoints(title1);
			predictedPoints = TimeValuePointManager.loadPoints(title2);
		}
		catch (IOException e)
		{
			IJ.error("Failed to load the points: " + e.getMessage());
			return;
		}
		d = dThreshold;

		// Optionally filter points using a mask
		if (myMask != null)
		{
			ImagePlus maskImp = WindowManager.getImage(myMask);
			if (maskImp != null)
			{
				actualPoints = filter(actualPoints, maskImp.getProcessor());
				predictedPoints = filter(predictedPoints, maskImp.getProcessor());
			}
		}

		compareCoordinates(actualPoints, predictedPoints, d);
	}

	private TimeValuedPoint[] filter(TimeValuedPoint[] points, ImageProcessor processor)
	{
		int ok = 0;
		for (int i = 0; i < points.length; i++)
		{
			if (processor.get(points[i].getXint(), points[i].getYint()) > 0)
			{
				points[ok++] = points[i];
			}
		}
		return Arrays.copyOf(points, ok);
	}

	/**
	 * Adds an ROI point overlay to the image using the specified colour
	 * 
	 * @param imp
	 * @param list
	 * @param color
	 */
	public static void addOverlay(ImagePlus imp, List<? extends Coordinate> list, Color color)
	{
		if (list.isEmpty())
			return;

		Color strokeColor = color;
		Color fillColor = color;

		Overlay o = imp.getOverlay();
		PointRoi roi = (PointRoi) PointManager.createROI(list);
		roi.setStrokeColor(strokeColor);
		roi.setFillColor(fillColor);
		roi.setHideLabels(true);

		if (o == null)
		{
			imp.setOverlay(roi, strokeColor, 2, fillColor);
		}
		else
		{
			o.add(roi);
			imp.setOverlay(o);
		}
	}

	private boolean showDialog()
	{
		ArrayList<String> newImageList = buildMaskList();
		final boolean haveImages = !newImageList.isEmpty();

		GenericDialog gd = new GenericDialog(TITLE);

		gd.addMessage(
				"Compare the points in two files\nand compute the match statistics\n(Double click input fields to use a file chooser)");
		gd.addStringField("Input_1", title1, 30);
		gd.addStringField("Input_2", title2, 30);
		if (haveImages)
		{
			ArrayList<String> maskImageList = new ArrayList<String>(newImageList.size() + 1);
			maskImageList.add("[None]");
			maskImageList.addAll(newImageList);
			gd.addChoice("mask", maskImageList.toArray(new String[0]), mask);
		}
		gd.addNumericField("Distance", dThreshold, 2);
		gd.addNumericField("Beta", beta, 2);
		gd.addCheckbox("Show_pairs", showPairs);
		gd.addCheckbox("Save_pairs", savePairs);
		gd.addMessage("Use this option to save the pairs to a single file,\nappending if it exists");
		gd.addCheckbox("Save_pairs_single_file", savePairsSingleFile);
		if (!newImageList.isEmpty())
		{
			gd.addCheckbox("Show_composite_image", showComposite);
			gd.addCheckbox("Ignore_file_frames", ignoreFrames);
			String[] items = newImageList.toArray(new String[newImageList.size()]);
			gd.addChoice("Image_1", items, image1);
			gd.addChoice("Image_2", items, image2);
			gd.addCheckbox("Use_slice_position", useSlicePosition);
		}

		// Dialog to allow double click to select files using a file chooser
		if (!java.awt.GraphicsEnvironment.isHeadless())
		{
			text1 = (TextField) gd.getStringFields().get(0);
			text2 = (TextField) gd.getStringFields().get(1);
			text1.addMouseListener(this);
			text2.addMouseListener(this);
		}

		gd.showDialog();

		if (gd.wasCanceled())
			return false;

		title1 = gd.getNextString();
		title2 = gd.getNextString();
		if (haveImages)
		{
			myMask = mask = gd.getNextChoice();
		}
		dThreshold = gd.getNextNumber();
		beta = gd.getNextNumber();
		showPairs = gd.getNextBoolean();
		savePairs = gd.getNextBoolean();
		savePairsSingleFile = gd.getNextBoolean();
		if (haveImages)
		{
			showComposite = gd.getNextBoolean();
			ignoreFrames = gd.getNextBoolean();
			myImage1 = image1 = gd.getNextChoice();
			myImage2 = image2 = gd.getNextChoice();
			useSlicePosition = gd.getNextBoolean();
		}

		return true;
	}

	public static ArrayList<String> buildMaskList()
	{
		ArrayList<String> newImageList = new ArrayList<String>();

		for (int id : gdsc.core.ij.Utils.getIDList())
		{
			ImagePlus imp = WindowManager.getImage(id);
			// Ignore RGB images
			if (imp.getBitDepth() == 24)
				continue;
			newImageList.add(imp.getTitle());
		}

		return newImageList;
	}

	private void compareCoordinates(TimeValuedPoint[] actualPoints, TimeValuedPoint[] predictedPoints,
			double dThreshold)
	{
		int tp = 0, fp = 0, fn = 0;
		double rmsd = 0;

		final boolean is3D = is3D(actualPoints) && is3D(predictedPoints);
		final boolean computePairs = showPairs || savePairs || savePairsSingleFile ||
				(showComposite && myImage1 != null && myImage2 != null);

		final List<PointPair> pairs = (computePairs) ? new LinkedList<PointPair>() : null;

		// Process each timepoint
		for (Integer t : getTimepoints(actualPoints, predictedPoints))
		{
			Coordinate[] actual = getCoordinates(actualPoints, t);
			Coordinate[] predicted = getCoordinates(predictedPoints, t);

			List<Coordinate> TP = null;
			List<Coordinate> FP = null;
			List<Coordinate> FN = null;
			List<PointPair> matches = null;
			if (computePairs)
			{
				TP = new LinkedList<Coordinate>();
				FP = new LinkedList<Coordinate>();
				FN = new LinkedList<Coordinate>();
				matches = new LinkedList<PointPair>();
			}

			MatchResult result = (is3D)
					? MatchCalculator.analyseResults3D(actual, predicted, dThreshold, TP, FP, FN, matches)
					: MatchCalculator.analyseResults2D(actual, predicted, dThreshold, TP, FP, FN, matches);

			// Aggregate
			tp += result.getTruePositives();
			fp += result.getFalsePositives();
			fn += result.getFalseNegatives();
			rmsd += (result.getRMSD() * result.getRMSD()) * result.getTruePositives();

			if (computePairs)
			{
				pairs.addAll(matches);
				for (Coordinate c : FN)
					pairs.add(new PointPair(c, null));
				for (Coordinate c : FP)
					pairs.add(new PointPair(null, c));
			}
		}

		if (showPairs || savePairs || savePairsSingleFile)
		{
			// Check if these are valued points
			valued = isValued(actualPoints) && isValued(predictedPoints);
		}

		if (!java.awt.GraphicsEnvironment.isHeadless())
		{
			if (resultsWindow == null || !resultsWindow.isShowing())
			{
				resultsWindow = new TextWindow(TITLE + " Results", createResultsHeader(), "", 900, 300);
			}
			if (showPairs)
			{
				String header = createPairsHeader(title1, title2);
				if (pairsWindow == null || !pairsWindow.isShowing())
				{
					pairsWindow = new TextWindow(TITLE + " Pairs", header, "", 900, 300);
					Point p = resultsWindow.getLocation();
					p.y += resultsWindow.getHeight();
					pairsWindow.setLocation(p);
				}
				for (PointPair pair : pairs)
					addPairResult(pair, is3D);
			}
		}
		else
		{
			if (writeHeader)
			{
				writeHeader = false;
				IJ.log(createResultsHeader());
			}
		}
		if (tp > 0)
			rmsd = Math.sqrt(rmsd / tp);
		MatchResult result = new MatchResult(tp, fp, fn, rmsd);
		addResult(title1, title2, dThreshold, result);

		if (savePairs || savePairsSingleFile)
		{
			savePairs(pairs, is3D);
		}

		if (java.awt.GraphicsEnvironment.isHeadless())
			return;

		// If input images and a mask have been selected then we can produce an output
		// that draws the points on a composite image.
		produceComposite(pairs);
	}

	/**
	 * Checks if there is more than one z-value in the coordinates
	 * 
	 * @param points
	 * @return
	 */
	private boolean is3D(TimeValuedPoint[] points)
	{
		if (points.length == 0)
			return false;
		final float z = points[0].getZ();
		for (TimeValuedPoint p : points)
			if (p.getZ() != z)
				return true;
		return false;
	}

	/**
	 * Checks if there is a non-zero value within the points
	 * 
	 * @param points
	 * @return
	 */
	private boolean isValued(TimeValuedPoint[] points)
	{
		if (points.length == 0)
			return false;
		for (TimeValuedPoint p : points)
			if (p.getValue() != 0)
				return true;
		return false;
	}

	private Collection<Integer> getTimepoints(TimeValuedPoint[] points, TimeValuedPoint[] points2)
	{
		Set<Integer> set;
		if (showPairs)
			set = new TreeSet<Integer>();
		else
			// The order is not critical so use a HashSet
			set = new HashSet<Integer>();
		for (TimeValuedPoint p : points)
			set.add(p.getTime());
		for (TimeValuedPoint p : points2)
			set.add(p.getTime());
		return set;
	}

	private Coordinate[] getCoordinates(TimeValuedPoint[] points, int t)
	{
		LinkedList<Coordinate> coords = new LinkedList<Coordinate>();
		int id = 1;
		for (TimeValuedPoint p : points)
			if (p.getTime() == t)
				coords.add(new IdTimeValuedPoint(id++, p));
		return coords.toArray(new Coordinate[coords.size()]);
	}

	private String createResultsHeader()
	{
		StringBuilder sb = new StringBuilder();
		sb.append("Image 1\t");
		sb.append("Image 2\t");
		sb.append("Distance (px)\t");
		sb.append("N\t");
		sb.append("TP\t");
		sb.append("FP\t");
		sb.append("FN\t");
		sb.append("Jaccard\t");
		sb.append("RMSD\t");
		sb.append("Precision\t");
		sb.append("Recall\t");
		sb.append("F0.5\t");
		sb.append("F1\t");
		sb.append("F2\t");
		sb.append("F-beta");
		return sb.toString();
	}

	private void addResult(String i1, String i2, double dThrehsold, MatchResult result)
	{
		StringBuilder sb = new StringBuilder();
		sb.append(i1).append("\t");
		sb.append(i2).append("\t");
		sb.append(IJ.d2s(dThrehsold, 2)).append("\t");
		sb.append(result.getNumberPredicted()).append("\t");
		sb.append(result.getTruePositives()).append("\t");
		sb.append(result.getFalsePositives()).append("\t");
		sb.append(result.getFalseNegatives()).append("\t");
		sb.append(IJ.d2s(result.getJaccard(), 4)).append("\t");
		sb.append(IJ.d2s(result.getRMSD(), 4)).append("\t");
		sb.append(IJ.d2s(result.getPrecision(), 4)).append("\t");
		sb.append(IJ.d2s(result.getRecall(), 4)).append("\t");
		sb.append(IJ.d2s(result.getFScore(0.5), 4)).append("\t");
		sb.append(IJ.d2s(result.getFScore(1.0), 4)).append("\t");
		sb.append(IJ.d2s(result.getFScore(2.0), 4)).append("\t");
		sb.append(IJ.d2s(result.getFScore(beta), 4));

		if (java.awt.GraphicsEnvironment.isHeadless())
		{
			IJ.log(sb.toString());
		}
		else
		{
			resultsWindow.append(sb.toString());
		}
	}

	private String pairsPrefix;

	private String createPairsHeader(String i1, String i2)
	{
		StringBuilder sb = new StringBuilder();
		sb.append(i1).append("\t");
		sb.append(i2).append("\t");
		pairsPrefix = sb.toString();

		sb.setLength(0);
		sb.append("Image 1\t");
		sb.append("Image 2\t");
		sb.append("T\t");
		sb.append("ID1\t");
		sb.append("X1\t");
		sb.append("Y1\t");
		sb.append("Z1\t");
		if (valued)
			sb.append("Value\t");
		sb.append("ID2\t");
		sb.append("X2\t");
		sb.append("Y2\t");
		sb.append("Z2\t");
		if (valued)
			sb.append("Value\t");
		sb.append("Distance\t");
		sb.append("Outcome");
		return sb.toString();
	}

	private void addPairResult(PointPair pair, boolean is3D)
	{
		pairsWindow.append(createPairResult(pair, is3D));
	}

	private String createPairResult(PointPair pair, boolean is3D)
	{
		StringBuilder sb = new StringBuilder(pairsPrefix);
		IdTimeValuedPoint p1 = (IdTimeValuedPoint) pair.getPoint1();
		IdTimeValuedPoint p2 = (IdTimeValuedPoint) pair.getPoint2();
		int t = (p1 != null) ? p1.getTime() : p2.getTime();
		sb.append(t).append("\t");
		addPoint(sb, p1);
		addPoint(sb, p2);
		double d = (is3D) ? pair.getXYZDistance() : pair.getXYDistance();
		if (d >= 0)
			sb.append(d).append("\t");
		else
			sb.append("\t");
		// Added for colocalisation analysis:
		// C = Colocalised (i.e. a match)
		// F = First dataset has foci
		// S = Second dataset has foci
		final char outcome = (p1 != null) ? (p2 != null) ? 'C' : 'F' : 'S';
		sb.append(outcome);
		return sb.toString();
	}

	private void addPoint(StringBuilder sb, IdTimeValuedPoint p)
	{
		if (p == null)
		{
			if (valued)
				sb.append("\t\t\t\t\t");
			else
				sb.append("\t\t\t\t");
		}
		else
		{
			sb.append(p.id).append("\t");
			sb.append(p.getXint()).append("\t");
			sb.append(p.getYint()).append("\t");
			sb.append(p.getZint()).append("\t");
			if (valued)
				sb.append(p.getValue()).append("\t");
		}
	}

	private void savePairs(List<PointPair> pairs, boolean is3D)
	{
		boolean fileSelected = false;
		if (savePairs)
		{
			filename = getFilename(filename);
			fileSelected = filename != null;
		}
		boolean fileSingleSelected = false;
		if (savePairsSingleFile)
		{
			filenameSingle = getFilename(filenameSingle);
			fileSingleSelected = filenameSingle != null;
		}
		if (!(fileSelected || fileSingleSelected))
			return;

		OutputStreamWriter out = null;
		OutputStreamWriter outSingle = null;
		try
		{
			final String newLine = System.getProperty("line.separator");

			// Always create the header as it sets up the pairs prefix for each pair result
			final String header = createPairsHeader(title1, title2);
			if (fileSelected)
			{
				FileOutputStream fos = new FileOutputStream(filename);
				out = new OutputStreamWriter(fos, "UTF-8");
				out.write(header);
				out.write(newLine);
			}
			if (fileSingleSelected)
			{
				File file = new File(filenameSingle);
				boolean append = (file.length() != 0);
				FileOutputStream fos = new FileOutputStream(file, append);
				outSingle = new OutputStreamWriter(fos, "UTF-8");
				if (!append)
				{
					outSingle.write(header);
					outSingle.write(newLine);
				}
			}

			for (PointPair pair : pairs)
			{
				final String result = createPairResult(pair, is3D);
				if (out != null)
				{
					out.write(result);
					out.write(newLine);
				}
				if (outSingle != null)
				{
					outSingle.write(result);
					outSingle.write(newLine);
				}
			}
		}
		catch (Exception e)
		{
			IJ.log("Unable to save the matches to file: " + e.getMessage());
		}
		finally
		{
			if (out != null)
			{
				try
				{
					out.close();
				}
				catch (IOException e)
				{
					// Ignore
				}
			}
			if (outSingle != null)
			{
				try
				{
					outSingle.close();
				}
				catch (IOException e)
				{
					// Ignore
				}
			}
		}
	}

	private String getFilename(String filename)
	{
		final String[] path = Utils.decodePath(filename);
		final OpenDialog chooser = new OpenDialog("matches_file", path[0], path[1]);
		if (chooser.getFileName() == null)
			return null;
		return Utils.replaceExtension(chooser.getDirectory() + chooser.getFileName(), ".xls");
	}

	public void mouseClicked(MouseEvent e)
	{
		if (e.getClickCount() > 1) // Double-click
		{
			TextField text;
			String title;
			if (e.getSource() == text1)
			{
				text = text1;
				title = "Coordinate_file_1";
			}
			else
			{
				text = text2;
				title = "Coordinate_file_2";
			}
			String[] path = decodePath(text.getText());
			OpenDialog chooser = new OpenDialog(title, path[0], path[1]);
			if (chooser.getFileName() != null)
			{
				text.setText(chooser.getDirectory() + chooser.getFileName());
			}
		}
	}

	private String[] decodePath(String path)
	{
		String[] result = new String[2];
		int i = path.lastIndexOf('/');
		if (i == -1)
			i = path.lastIndexOf('\\');
		if (i > 0)
		{
			result[0] = path.substring(0, i + 1);
			result[1] = path.substring(i + 1);
		}
		else
		{
			result[0] = OpenDialog.getDefaultDirectory();
			result[1] = path;
		}
		return result;
	}

	public void mousePressed(MouseEvent e)
	{

	}

	public void mouseReleased(MouseEvent e)
	{

	}

	public void mouseEntered(MouseEvent e)
	{

	}

	public void mouseExited(MouseEvent e)
	{

	}

	private void produceComposite(List<PointPair> pairs)
	{
		if (!showComposite)
			return;
		if (myImage1 == null || myImage2 == null)
			return;
		ImagePlus imp1 = WindowManager.getImage(myImage1);
		ImagePlus imp2 = WindowManager.getImage(myImage2);
		ImagePlus impM = WindowManager.getImage(myMask);
		if (myImage1 == null || myImage2 == null)
		{
			IJ.error(TITLE, "No images specified for composite");
			return;
		}
		// Images must be the same XYZ dimensions
		final int w = imp1.getWidth();
		final int h = imp1.getHeight();
		final int nSlices = imp1.getNSlices();
		if (w != imp2.getWidth() || h != imp2.getHeight() || nSlices != imp2.getNSlices())
		{
			IJ.error(TITLE, "Composite images must have the same XYZ dimensions");
			return;
		}
		if (impM != null && (w != impM.getWidth() || h != impM.getHeight()))
		{
			IJ.error(TITLE, "Composite image mask must have the same XY dimensions");
			return;
		}

		final boolean addMask = impM != null;

		final ImageStack stack = new ImageStack(w, h);
		final ImageStack s1 = imp1.getImageStack();
		final ImageStack s2 = imp2.getImageStack();
		final ImageStack sm = (addMask) ? impM.getImageStack() : null;

		// Get the bit-depth for the output stack
		int depth = imp1.getBitDepth();
		depth = Math.max(depth, imp2.getBitDepth());
		if (addMask)
			depth = Math.max(depth, impM.getBitDepth());

		final int nChannels = (addMask) ? 3 : 2;
		int nFrames = 0;

		// Produce a composite for each time point.
		// The input list will have pairs in order of time.
		// So move through the list noting each new time.
		ArrayList<Integer> upper = new ArrayList<Integer>();

		int time = -1;
		final IdTimeValuedPoint[] p1 = new IdTimeValuedPoint[pairs.size()];
		final IdTimeValuedPoint[] p2 = new IdTimeValuedPoint[pairs.size()];
		for (int i = 0; i < pairs.size(); i++)
		{
			p1[i] = (IdTimeValuedPoint) pairs.get(i).getPoint1();
			p2[i] = (IdTimeValuedPoint) pairs.get(i).getPoint2();
			final int newTime = (p1[i] != null) ? p1[i].time : p2[i].time;
			if (time != newTime)
			{
				if (time != -1)
					upper.add(i);
				time = newTime;
			}
		}
		upper.add(pairs.size());

		// Check if the input image has only one time frame but the points have multiple time points
		boolean singleFrame = false;
		if (upper.size() > 1 && imp1.getNFrames() == 1 && imp2.getNFrames() == 1)
		{
			if (ignoreFrames)
			{
				singleFrame = true;
				upper.clear();
				upper.add(pairs.size());
			}
		}

		// Create an overlay to show the pairs
		Overlay overlay = new Overlay();
		final Color colorf = MatchPlugin.UNMATCH1;
		final Color colors = MatchPlugin.UNMATCH2;
		final Color colorc = MatchPlugin.MATCH;

		int l = 0;
		for (int u : upper)
		{
			nFrames++;
			time = (p1[l] != null) ? p1[l].time : p2[l].time;
			//System.out.printf("%d - %d : Time %d\n", l, u, time);
			if (singleFrame)
				time = 1;

			// Extract the images for the specified time
			for (int slice = 1; slice <= nSlices; slice++)
			{
				stack.addSlice("Image1, t=" + time,
						convert(s1.getProcessor(imp1.getStackIndex(imp1.getC(), slice, time)), depth));
				stack.addSlice("Image2, t=" + time,
						convert(s2.getProcessor(imp2.getStackIndex(imp2.getC(), slice, time)), depth));
				if (addMask)
					stack.addSlice("Mask, t=" + time,
							convert(sm.getProcessor(impM.getStackIndex(impM.getC(), slice, time)), depth));
			}

			// Count number of First, Second, Colocalised.
			int f = 0, s = 0, c = 0;
			float[] fx = new float[u - l];
			float[] fy = new float[fx.length];
			float[] sx = new float[fx.length];
			float[] sy = new float[fx.length];
			float[] cx = new float[fx.length];
			float[] cy = new float[fx.length];
			int[] fz = new int[fx.length];
			int[] sz = new int[fx.length];
			int[] cz = new int[fx.length];
			for (int j = l; j < u; j++)
			{
				if (p1[j] == null)
				{
					sx[s] = p2[j].getX();
					sy[s] = p2[j].getY();
					sz[s] = p2[j].getZint();
					s++;
				}
				else if (p2[j] == null)
				{
					fx[f] = p1[j].getX();
					fy[f] = p1[j].getY();
					fz[f] = p1[j].getZint();
					f++;
				}
				else
				{
					cx[c] = (p1[j].getX() + p2[j].getX()) * 0.5f;
					cy[c] = (p1[j].getY() + p2[j].getY()) * 0.5f;
					cz[c] = (p1[j].getZint() + p2[j].getZint()) / 2;
					c++;
				}
			}

			add(overlay, fx, fy, fz, f, colorf, nFrames);
			add(overlay, sx, sy, sz, s, colors, nFrames);
			add(overlay, cx, cy, cz, c, colorc, nFrames);

			l = u;
		}

		String title = "Match Composite";
		ImagePlus imp = new ImagePlus(title, stack);
		imp.setDimensions(nChannels, nSlices, nFrames);
		imp.setOverlay(overlay);
		imp.setOpenAsHyperStack(true);

		imp2 = WindowManager.getImage(title);
		if (imp2 != null)
		{
			if (imp2.isDisplayedHyperStack())
			{
				imp2.setImage(imp);
				imp2.setOverlay(overlay);
				imp2.getWindow().toFront();
				return;
			}
			// Figure out how to convert back to a hyperstack if it is not already.
			// Currently we just close the image and show a new one.
			imp2.close();
		}

		imp.show();
	}

	private ImageProcessor convert(ImageProcessor processor, int depth)
	{
		if (depth == 8)
			return processor.convertToByte(true);
		if (depth == 16)
			return processor.convertToShort(true);
		return processor.convertToFloat();
	}

	private void add(Overlay overlay, float[] x, float[] y, int[] z, int n, Color color, int frame)
	{
		if (n != 0)
		{
			// Option to position the ROI points on the z-slice.
			// This requires an ROI for each slice that contains a point.
			if (useSlicePosition)
			{
				// Sort points by slice position
				float[][] data = new float[n][3];
				for (int i = n; i-- > 0;)
				{
					data[i][0] = z[i];
					data[i][1] = x[i];
					data[i][2] = y[i];
				}

				Comparator<float[]> comp = new Comparator<float[]>()
				{
					public int compare(float[] o1, float[] o2)
					{
						// smallest first
						if (o1[0] < o2[0])
							return -1;
						if (o1[0] > o2[0])
							return 1;
						return 0;
					}
				};

				Arrays.sort(data, comp);

				// Find blocks in the same slice
				int l = 0;
				ArrayList<Integer> upper = new ArrayList<Integer>(n);
				for (int i = 0; i < n; i++)
				{
					if (data[l][0] != data[i][0])
					{
						upper.add(i);
						l = i;
					}
				}
				upper.add(n);

				// Process each block
				l = 0;
				for (int u : upper)
				{
					int nPoints = u - l;
					for (int i = l, j = 0; i < u; i++, j++)
					{
						x[j] = data[i][1];
						y[j] = data[i][2];
					}
					add(overlay, new PointRoi(x, y, nPoints), (int) data[l][0], frame, color);
					l = u;
				}
			}
			else
			{
				add(overlay, new PointRoi(x, y, n), 0, frame, color);
			}
		}
	}

	private void add(Overlay overlay, PointRoi roi, int slice, int frame, Color color)
	{
		// Tie position to the frame but not the channel or slice
		//System.out.printf("Add %d to z=%d,t=%d\n", roi.getNCoordinates(), slice, frame);
		roi.setPosition(0, slice, frame);
		roi.setStrokeColor(color);
		roi.setFillColor(color);
		roi.setHideLabels(true);
		overlay.add(roi);
	}
}