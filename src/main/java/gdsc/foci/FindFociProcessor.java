package gdsc.foci;

/*----------------------------------------------------------------------------- 
 * GDSC Plugins for ImageJ
 * 
 * Copyright (C) 2016 Alex Herbert
 * Genome Damage and Stability Centre
 * University of Sussex, UK
 * 
 * This program is free software; you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation; either version 2 of the License, or
 * (at your option) any later version.
 *---------------------------------------------------------------------------*/

import java.awt.Rectangle;
import java.awt.geom.RoundRectangle2D;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedList;

import gdsc.core.ij.Utils;
import gdsc.core.threshold.AutoThreshold;
import gdsc.threshold.Multi_OtsuThreshold;
import gdsc.utils.GaussianFit;
import ij.IJ;
import ij.ImagePlus;
import ij.ImageStack;
import ij.gui.Roi;
import ij.process.ImageProcessor;

/**
 * Find the peak intensity regions of an image.
 * 
 * <P>
 * All local maxima above threshold are identified. For all other pixels the direction to the highest neighbour pixel is
 * stored (steepest gradient). In order of highest local maxima, regions are only grown down the steepest gradient to a
 * lower pixel. Provides many configuration options for regions growing thresholds.
 * 
 * <P>
 * This plugin was based on {@link ij.plugin.filter.MaximumFinder}. Options have been changed to only support greyscale
 * 2D images and 3D stacks and to perform region growing using configurable thresholds. Support for Watershed,
 * Previewing, and Euclidian Distance Map (EDM) have been removed.
 * 
 * <P>
 * Stopping criteria for region growing routines are partly based on the options in PRIISM
 * (http://www.msg.ucsf.edu/IVE/index.html).
 */
public abstract class FindFociProcessor
{
	/**
	 * The largest number that can be displayed in a 16-bit image.
	 * <p>
	 * Note searching for maxima uses 32-bit integers but ImageJ can only display 16-bit images.
	 */
	private static final int MAXIMA_CAPCITY = 65535;

	// The indices used within the saddle array
	private final static int SADDLE_PEAK_ID = 0;
	private final static int SADDLE_VALUE = 1;

	// the following are class variables for having shorter argument lists
	protected int maxx, maxy, maxz; // image dimensions
	protected int xlimit, ylimit, zlimit;
	protected int maxx_maxy, maxx_maxy_maxz;
	protected int[] offset;
	protected int dStart;
	protected boolean[] flatEdge;
	private Rectangle bounds = null;

	// The following arrays are built for a 3D search through the following z-order: (0,-1,1)
	// Each 2D plane is built for a search round a pixel in an anti-clockwise direction. 
	// Note the x==y==z==0 element is not present. Thus there are blocks of 8,9,9 for each plane.
	// This preserves the isWithin() functionality of ij.plugin.filter.MaximumFinder.

	//@formatter:off
	protected final int[] DIR_X_OFFSET = new int[] { 0, 1, 1, 1, 0,-1,-1,-1, 0, 1, 1, 1, 0,-1,-1,-1, 0, 0, 1, 1, 1, 0,-1,-1,-1, 0 };
	protected final int[] DIR_Y_OFFSET = new int[] {-1,-1, 0, 1, 1, 1, 0,-1,-1,-1, 0, 1, 1, 1, 0,-1, 0,-1,-1, 0, 1, 1, 1, 0,-1, 0 };
	protected final int[] DIR_Z_OFFSET = new int[] { 0, 0, 0, 0, 0, 0, 0, 0,-1,-1,-1,-1,-1,-1,-1,-1,-1, 1, 1, 1, 1, 1, 1, 1, 1, 1 };
	//@formatter:on

	/* the following constants are used to set bits corresponding to pixel types */
	protected final static byte EXCLUDED = (byte) 1; // marks points outside the ROI
	protected final static byte MAXIMUM = (byte) 2; // marks local maxima (irrespective of noise tolerance)
	protected final static byte LISTED = (byte) 4; // marks points currently in the list
	protected final static byte MAX_AREA = (byte) 8; // marks areas near a maximum, within the tolerance
	protected final static byte SADDLE = (byte) 16; // marks a potential saddle between maxima
	protected final static byte SADDLE_POINT = (byte) 32; // marks a saddle between maxima
	protected final static byte SADDLE_WITHIN = (byte) 64; // marks a point within a maxima next to a saddle
	protected final static byte PLATEAU = (byte) 128; // marks a point as a plateau region

	protected final static byte BELOW_SADDLE = (byte) 128; // marks a point as falling below the highest saddle point

	protected final static byte IGNORE = EXCLUDED | LISTED; // marks point to be ignored in stage 1

	private final ImagePlus imp;

	public FindFociProcessor(ImagePlus imp)
	{
		this.imp = imp;
	}

	/**
	 * Here the processing is done: Find the maxima of an image.
	 * 
	 * <P>
	 * Local maxima are processed in order, highest first. Regions are grown from local maxima until a saddle point is
	 * found or the stopping criteria are met (based on pixel intensity). If a peak does not meet the peak criteria (min
	 * size) it is absorbed into the highest peak that touches it (if a neighbour peak exists). Only a single iteration
	 * is performed and consequently peak absorption could produce sub-optimal results due to greedy peak growth.
	 * 
	 * <P>
	 * Peak expansion stopping criteria are defined using the method parameter. See {@link #SEARCH_ABOVE_BACKGROUND};
	 * {@link #SEARCH_FRACTION_OF_PEAK_MINUS_BACKGROUND}; {@link #SEARCH_HALF_PEAK_VALUE};
	 * {@link #SEARCH_STD_DEV_FROM_BACKGROUND}
	 * 
	 * @param mask
	 *            A mask image used to define the region to search for peaks
	 * @param backgroundMethod
	 *            Method for calculating the background level (use the constants with prefix FindFoci.BACKGROUND_)
	 * @param backgroundParameter
	 *            parameter for calculating the background level
	 * @param autoThresholdMethod
	 *            The thresholding method (use a string from {@link #autoThresholdMethods } )
	 * @param searchMethod
	 *            Method for calculating the region growing stopping criteria (use the constants with prefix SEARCH_)
	 * @param searchParameter
	 *            parameter for calculating the stopping criteria
	 * @param maxPeaks
	 *            The maximum number of peaks to report
	 * @param minSize
	 *            The minimum size for a peak
	 * @param peakMethod
	 *            Method for calculating the minimum peak height above the highest saddle (use the constants with prefix
	 *            PEAK_)
	 * @param peakParameter
	 *            parameter for calculating the minimum peak height
	 * @param outputType
	 *            Use {@link #FindFoci.OUTPUT_MASK_PEAKS} to get an ImagePlus in the result Object array. Use
	 *            {@link #FindFoci.OUTPUT_LOG_MESSAGES} to get runtime information.
	 * @param sortIndex
	 *            The index of the result statistic to use for the peak sorting
	 * @param options
	 *            An options flag (use the constants with prefix FindFoci.OPTION_)
	 * @param blur
	 *            Apply a Gaussian blur of the specified radius before processing (helps smooth noisy images for better
	 *            peak identification)
	 * @param centreMethod
	 *            Define the method used to calculate the peak centre (use the constants with prefix
	 *            FindFoci.FindFoci.CENTRE_)
	 * @param centreParameter
	 *            Parameter for calculating the peak centre
	 * @param fractionParameter
	 *            Used to specify the fraction of the peak to show in the mask
	 * @return Result containing: (1) a new ImagePlus (with a stack) where the maxima are set to nMaxima+1 and
	 *         peak areas numbered starting from nMaxima (Background 0). Pixels outside of the roi of the input ip are
	 *         not set. Alternatively the peak areas can be thresholded using the auto-threshold method and coloured
	 *         1(saddle), 2(background), 3(threshold), 4(peak); (2) a result ArrayList<double[]> with details of the
	 *         maxima. The details can be extracted for each result using the constants defined with the prefix
	 *         FindFoci.RESULT_;
	 *         (3) the image statistics double[] array. The details can be extracted using the constants defined with
	 *         the FindFoci.STATS_ prefix. Returns null if cancelled by escape.
	 */
	FindFociResult findMaxima(ImagePlus mask, int backgroundMethod, double backgroundParameter,
			String autoThresholdMethod, int searchMethod, double searchParameter, int maxPeaks, int minSize,
			int peakMethod, double peakParameter, int outputType, int sortIndex, int options, double blur,
			int centreMethod, double centreParameter, double fractionParameter)
	{
		boolean isLogging = isLogging(outputType);

		if (isLogging)
			IJ.log("---" + FindFoci.newLine + FindFoci.TITLE + " : " + imp.getTitle());

		// Call first to set up the processing for isWithin;
		initialise(imp);
		IJ.resetEscape();
		long start = System.currentTimeMillis();
		timingStart();
		final boolean restrictAboveSaddle = (options &
				FindFoci.OPTION_MINIMUM_ABOVE_SADDLE) == FindFoci.OPTION_MINIMUM_ABOVE_SADDLE;

		IJ.showStatus("Initialising memory...");

		// Support int[] or float[] image
		final Object originalImage = extractImage(imp);
		final Object image;

		if (blur > 0)
		{
			// Apply a Gaussian pre-processing step
			image = extractImage(FindFoci.applyBlur(imp, blur));
		}
		else
		{
			// The images are the same so just copy the reference
			image = originalImage;
		}

		final byte[] types = new byte[maxx_maxy_maxz]; // Will be a notepad for pixel types
		final int[] maxima = new int[maxx_maxy_maxz]; // Contains the maxima Id assigned for each point

		IJ.showStatus("Initialising ROI...");

		// Mark any point outside the ROI as processed
		int exclusion = excludeOutsideROI(imp, types, isLogging);
		exclusion += excludeOutsideMask(mask, types, isLogging);

		// The histogram is used to process the levels in the assignPointsToMaxima() routine. 
		// So only use those that have not been excluded.
		IJ.showStatus("Building histogram...");

		final Histogram histogram = buildHistogram(imp.getBitDepth(), image, types, FindFoci.OPTION_STATS_INSIDE);
		final double[] stats = getStatistics(histogram);

		Histogram statsHistogram = histogram;

		// Set to both by default
		if ((options & (FindFoci.OPTION_STATS_INSIDE | FindFoci.OPTION_STATS_OUTSIDE)) == 0)
			options |= FindFoci.OPTION_STATS_INSIDE | FindFoci.OPTION_STATS_OUTSIDE;

		// Allow the threshold to be set using pixels outside the mask/ROI, or both inside and outside.
		if (exclusion > 0 && (options & FindFoci.OPTION_STATS_OUTSIDE) != 0)
		{
			if ((options & FindFoci.OPTION_STATS_INSIDE) != 0)
			{
				// Both inside and outside
				statsHistogram = buildHistogram(imp.getBitDepth(), image);
			}
			else
			{
				statsHistogram = buildHistogram(imp.getBitDepth(), image, types, FindFoci.OPTION_STATS_OUTSIDE);
			}
			double[] newStats = getStatistics(statsHistogram);
			for (int i = 0; i < 4; i++)
				stats[i + FindFoci.STATS_MIN_BACKGROUND] = newStats[i + FindFoci.STATS_MIN];
		}

		if (isLogging)
			recordStatistics(stats, exclusion, options);

		if (Utils.isInterrupted())
			return null;

		// Calculate the auto-threshold if necessary
		if (backgroundMethod == FindFoci.BACKGROUND_AUTO_THRESHOLD)
		{
			stats[FindFoci.STATS_BACKGROUND] = getThreshold(autoThresholdMethod, statsHistogram);
		}
		statsHistogram = null;

		IJ.showStatus("Getting sorted maxima...");
		stats[FindFoci.STATS_BACKGROUND] = getSearchThreshold(backgroundMethod, backgroundParameter, stats);
		if (isLogging)
			IJ.log("Background level = " + IJ.d2s(stats[FindFoci.STATS_BACKGROUND], 2));
		Coordinate[] maxPoints = getSortedMaxPoints(image, maxima, types, (float) stats[FindFoci.STATS_MIN],
				(float) stats[FindFoci.STATS_BACKGROUND]);

		if (Utils.isInterrupted() || maxPoints == null)
			return null;

		if (isLogging)
			IJ.log("Number of potential maxima = " + maxPoints.length);
		IJ.showStatus("Analyzing maxima...");

		ArrayList<double[]> resultsArray = new ArrayList<double[]>(maxPoints.length);

		assignMaxima(maxima, maxPoints, resultsArray);

		// Free memory
		maxPoints = null;

		assignPointsToMaxima(image, histogram, types, stats, maxima);

		if (Utils.isInterrupted())
			return null;

		if (isLogging)
			timingSplit("Assigned maxima");

		// Remove points below the peak growth criteria
		pruneMaxima(image, types, searchMethod, searchParameter, stats, resultsArray, maxima);

		// Calculate the initial results (peak size and intensity)
		calculateInitialResults(image, maxima, resultsArray);

		IJ.showStatus("Finding saddle points...");

		// Calculate the highest saddle point for each peak
		ArrayList<LinkedList<double[]>> saddlePoints = new ArrayList<LinkedList<double[]>>(resultsArray.size() + 1);
		findSaddlePoints(image, types, resultsArray, maxima, saddlePoints);

		if (Utils.isInterrupted())
			return null;

		// Find the peak sizes above their saddle points.
		analysePeaks(resultsArray, image, maxima, stats);

		if (isLogging)
			timingSplit("Mapped saddle points");

		IJ.showStatus("Merging peaks...");

		// Combine maxima below the minimum peak criteria to adjacent peaks (or eliminate if no neighbours)
		int originalNumberOfPeaks = resultsArray.size();
		resultsArray = mergeSubPeaks(resultsArray, image, maxima, minSize, peakMethod, peakParameter, stats,
				saddlePoints, isLogging, restrictAboveSaddle);

		if (isLogging)
			timingSplit("Merged peaks");

		if (resultsArray == null || Utils.isInterrupted())
			return null;

		if ((options & FindFoci.OPTION_REMOVE_EDGE_MAXIMA) != 0)
			resultsArray = removeEdgeMaxima(resultsArray, maxima, stats, isLogging);

		final int totalPeaks = resultsArray.size();

		if (blur > 0)
		{
			// Recalculate the totals but do not update the saddle values 
			// (since basing these on the blur image should give smoother saddle results).
			calculateNativeResults(originalImage, maxima, resultsArray, originalNumberOfPeaks);
		}
		else
		{
			// If no blur was applied, then the centre using the original image will be the same as using the search
			if (centreMethod == FindFoci.CENTRE_MAX_VALUE_ORIGINAL)
				centreMethod = FindFoci.CENTRE_MAX_VALUE_SEARCH;
		}

		stats[FindFoci.STATS_SUM_ABOVE_BACKGROUND] = getIntensityAboveBackground(originalImage, types,
				stats[FindFoci.STATS_BACKGROUND]);

		// Calculate the peaks centre and maximum value.
		locateMaxima(originalImage, image, maxima, types, resultsArray, originalNumberOfPeaks, centreMethod,
				centreParameter);

		// Calculate the average intensity and values minus background
		calculateFinalResults(resultsArray, stats[FindFoci.STATS_BACKGROUND]);

		// Reorder the results
		sortDescResults(resultsArray, sortIndex, stats);

		// Only return the best results
		resultsArray = trim(resultsArray, maxPeaks);

		int nMaxima = resultsArray.size();
		if (isLogging)
		{
			String message = "Final number of peaks = " + nMaxima;
			if (nMaxima < totalPeaks)
				message += " / " + totalPeaks;
			IJ.log(message);
		}

		if (isLogging)
			timingSplit("Calulated results");

		// Build the output mask
		ImagePlus outImp = null;
		if ((outputType & FindFoci.CREATE_OUTPUT_MASK) != 0)
		{
			IJ.showStatus("Generating mask image...");

			outImp = generateOutputMask(outputType, autoThresholdMethod, imp, fractionParameter, image, types, maxima,
					stats, resultsArray, nMaxima);

			if (outImp == null)
				IJ.error(FindFoci.TITLE, "Too many maxima to display in a 16-bit image: " + nMaxima);

			if (isLogging)
				timingSplit("Calulated output mask");
		}

		renumberPeaks(resultsArray, originalNumberOfPeaks);

		IJ.showTime(imp, start, "Done ", maxz);

		return new FindFociResult(outImp, resultsArray, stats);
	}

	private float getThreshold(String autoThresholdMethod, Histogram histogram)
	{
		if (histogram instanceof FloatHistogram)
		{
			// Convert to a smaller histogram 
			histogram = histogram.compact(65536);
		}
		int[] statsHistogram = histogram.h;
		int t;
		if (autoThresholdMethod.endsWith("evel"))
		{
			Multi_OtsuThreshold multi = new Multi_OtsuThreshold();
			multi.ignoreZero = false;
			int level = autoThresholdMethod.contains("_3_") ? 3 : 4;
			int[] threshold = multi.calculateThresholds(statsHistogram, level);
			t = threshold[1];
		}
		else
		{
			t = AutoThreshold.getThreshold(autoThresholdMethod, statsHistogram);
		}
		// Convert back to an image value
		//System.out.printf("bin = %d, value = %f\n", t, histogram.getValue(t));
		return histogram.getValue(t);
	}

	private long timestamp;

	private void timingStart()
	{
		timestamp = System.nanoTime();
	}

	private void timingSplit(String string)
	{
		long newTimestamp = System.nanoTime();
		IJ.log(string + " = " + ((newTimestamp - timestamp) / 1000000.0) + " msec");
		timestamp = newTimestamp;
	}

	/**
	 * Extract the image into a linear array stacked in zyx order
	 */
	protected abstract Object extractImage(ImagePlus imp);

	/**
	 * This method is a stripped-down version of the
	 * {@link #findMaxima(ImagePlus, int, double, String, int, double, int, int, int, double, int, int, int, double, int, double)}
	 * routine.
	 * It does not support logging, interruption or mask generation. The method initialises the system up to the point
	 * of background generation. The result object can be cloned and passed multiple times to the
	 * {@link #findMaximaRun(Object[], int, double, int, double, int, int, int, double, int, int, double, int, double)}
	 * method.
	 * 
	 * <p>
	 * This method is intended for benchmarking.
	 * 
	 * @return Object array containing: (1) Object - image pixels; (2) byte[] - types array; (3) short[] - maxima array;
	 *         (4)
	 *         Histogram - image histogram; (5) double[] - image statistics; (6) Object - the original image pixels; (7)
	 *         ImagePlus -
	 *         the original image
	 */
	Object[] findMaximaInit(ImagePlus originalImp, ImagePlus imp, ImagePlus mask, int backgroundMethod,
			String autoThresholdMethod, int options)
	{
		// Call first to set up the processing for isWithin;
		initialise(imp);

		Object originalImage = extractImage(originalImp);
		Object image = extractImage(imp);
		byte[] types = new byte[maxx_maxy_maxz]; // Will be a notepad for pixel types
		int[] maxima = new int[maxx_maxy_maxz]; // Contains the maxima Id assigned for each point

		// Mark any point outside the ROI as processed
		int exclusion = excludeOutsideROI(originalImp, types, false);
		exclusion += excludeOutsideMask(mask, types, false);

		final Histogram histogram = buildHistogram(imp.getBitDepth(), image, types, FindFoci.OPTION_STATS_INSIDE);
		final double[] stats = getStatistics(histogram);

		Histogram statsHistogram = histogram;

		// Set to both by default
		if ((options & (FindFoci.OPTION_STATS_INSIDE | FindFoci.OPTION_STATS_OUTSIDE)) == 0)
			options |= FindFoci.OPTION_STATS_INSIDE | FindFoci.OPTION_STATS_OUTSIDE;

		// Allow the threshold to be set using pixels outside the mask/ROI, or both inside and outside.
		if (exclusion > 0 && (options & FindFoci.OPTION_STATS_OUTSIDE) != 0)
		{
			if ((options & FindFoci.OPTION_STATS_INSIDE) != 0)
			{
				// Both inside and outside
				statsHistogram = buildHistogram(imp.getBitDepth(), image);
			}
			else
			{
				statsHistogram = buildHistogram(imp.getBitDepth(), image, types, FindFoci.OPTION_STATS_OUTSIDE);
			}
			double[] newStats = getStatistics(statsHistogram);
			for (int i = 0; i < 4; i++)
				stats[i + FindFoci.STATS_MIN_BACKGROUND] = newStats[i + FindFoci.STATS_MIN];
		}

		// Calculate the auto-threshold if necessary
		if (backgroundMethod == FindFoci.BACKGROUND_AUTO_THRESHOLD)
		{
			stats[FindFoci.STATS_BACKGROUND] = getThreshold(autoThresholdMethod, statsHistogram);
		}

		return new Object[] { image, types, maxima, histogram, stats, originalImage, originalImp };
	}

	/**
	 * Clones the init array for use in
	 * {@link #findMaximaRun(Object[], int, double, int, double, int, int, int, double, int, int) }.
	 * Only the elements that are destructively modified by findMaximaRun are duplicated. The rest are shallow copied.
	 * 
	 * @param initArray
	 *            The original init array
	 * @param clonedInitArray
	 *            A previously cloned init array (avoid reallocating memory). Can be null.
	 * @return The cloned array
	 */
	static Object[] cloneInitArray(Object[] initArray, Object[] clonedInitArray)
	{
		Object image = initArray[0];
		byte[] types = (byte[]) initArray[1];
		int[] maxima = (int[]) initArray[2];
		Histogram histogram = (Histogram) initArray[3];
		double[] stats = (double[]) initArray[4];
		Object originalImage = initArray[5];
		ImagePlus imp = (ImagePlus) initArray[6];

		byte[] types2 = null;
		int[] maxima2 = null;
		Histogram histogram2 = histogram.clone();
		double[] stats2 = null;

		if (clonedInitArray == null)
		{
			clonedInitArray = new Object[7];
			types2 = new byte[types.length];
			maxima2 = new int[maxima.length];
			stats2 = new double[stats.length];
		}
		else
		{
			// Re-use arrays
			types2 = (byte[]) clonedInitArray[1];
			maxima2 = (int[]) clonedInitArray[2];
			stats2 = (double[]) clonedInitArray[4];

			// Maxima should be all zeros
			final short zero = 0;
			Arrays.fill(maxima2, zero);
		}

		// Copy the arrays that are destructively modified 
		System.arraycopy(types, 0, types2, 0, types.length);
		System.arraycopy(stats, 0, stats2, 0, stats.length);

		// Image is unchanged so this is not copied
		clonedInitArray[0] = image;
		clonedInitArray[1] = types2;
		clonedInitArray[2] = maxima2;
		clonedInitArray[3] = histogram2;
		clonedInitArray[4] = stats2;
		clonedInitArray[5] = originalImage;
		clonedInitArray[6] = imp;

		return clonedInitArray;
	}

	/**
	 * Clones the init array for use in findMaxima staged methods.
	 * Only the elements that are destructively modified by findMaximaRun are duplicated. The rest are shallow copied.
	 * 
	 * @param initArray
	 *            The original init array
	 * @param clonedInitArray
	 *            A previously cloned init array (avoid reallocating memory). Can be null.
	 * @return The cloned array
	 */
	static Object[] cloneResultsArray(Object[] initArray, Object[] clonedInitArray)
	{
		Object image = initArray[0];
		byte[] types = (byte[]) initArray[1];
		int[] maxima = (int[]) initArray[2];
		Histogram histogram = (Histogram) initArray[3];
		double[] stats = (double[]) initArray[4];
		Object originalImage = initArray[5];
		ImagePlus imp = (ImagePlus) initArray[6];

		byte[] types2 = null;
		int[] maxima2 = null;
		Histogram histogram2 = histogram.clone();
		double[] stats2 = null;

		if (clonedInitArray == null)
		{
			clonedInitArray = new Object[7];
			types2 = new byte[types.length];
			maxima2 = new int[maxima.length];
			stats2 = new double[stats.length];
		}
		else
		{
			types2 = (byte[]) clonedInitArray[1];
			maxima2 = (int[]) clonedInitArray[2];
			stats2 = (double[]) clonedInitArray[4];
		}

		// Copy the arrays that are destructively modified 
		System.arraycopy(types, 0, types2, 0, types.length);
		System.arraycopy(maxima, 0, maxima2, 0, maxima.length);
		System.arraycopy(stats, 0, stats2, 0, stats.length);

		// Image is unchanged so this is not copied
		clonedInitArray[0] = image;
		clonedInitArray[1] = types2;
		clonedInitArray[2] = maxima2;
		clonedInitArray[3] = histogram2;
		clonedInitArray[4] = stats2;
		clonedInitArray[5] = originalImage;
		clonedInitArray[6] = imp;

		return clonedInitArray;
	}

	/**
	 * This method is a stripped-down version of the
	 * {@link #findMaxima(ImagePlus, int, double, String, int, double, int, int, int, double, int, int, int, double)}
	 * routine.
	 * It does not support logging, interruption or mask generation. Only the result array is generated.
	 * 
	 * <p>
	 * The method must be called with the output from
	 * {@link #findMaximaInit(ImagePlus, int, double, String, int, double, int, int, int, double, int, int, int)}
	 * 
	 * <p>
	 * This method is intended for benchmarking.
	 * 
	 * @param initArray
	 *            The output from
	 *            {@link #findMaximaInit(ImagePlus, int, double, String, int, double, int, int, int, double, int, int, int)}
	 * @return Object array containing: (1) a result ArrayList<double[]> with details of the
	 *         maxima. The details can be extracted for each result using the constants defined with the prefix
	 *         FindFoci.RESULT_;
	 *         (2) Integer with the original number of peaks before merging.
	 */
	Object[] findMaximaRun(Object[] initArray, int backgroundMethod, double backgroundParameter, int searchMethod,
			double searchParameter, int minSize, int peakMethod, double peakParameter, int sortIndex, int options,
			double blur)
	{
		boolean restrictAboveSaddle = (options &
				FindFoci.OPTION_MINIMUM_ABOVE_SADDLE) == FindFoci.OPTION_MINIMUM_ABOVE_SADDLE;

		Object image = initArray[0];
		byte[] types = (byte[]) initArray[1]; // Will be a notepad for pixel types
		int[] maxima = (int[]) initArray[2]; // Contains the maxima Id assigned for each point
		Histogram histogram = (Histogram) initArray[3];
		double[] stats = (double[]) initArray[4];
		Object originalImage = initArray[5];

		setPixels(image);
		stats[FindFoci.STATS_BACKGROUND] = getSearchThreshold(backgroundMethod, backgroundParameter, stats);
		stats[FindFoci.STATS_SUM_ABOVE_BACKGROUND] = getIntensityAboveBackground(image, types,
				round(stats[FindFoci.STATS_BACKGROUND]));
		Coordinate[] maxPoints = getSortedMaxPoints(image, maxima, types, round(stats[FindFoci.STATS_MIN]),
				round(stats[FindFoci.STATS_BACKGROUND]));

		if (maxPoints == null)
			return null;

		ArrayList<double[]> resultsArray = new ArrayList<double[]>(maxPoints.length);

		assignMaxima(maxima, maxPoints, resultsArray);

		// Free memory
		maxPoints = null;

		assignPointsToMaxima(image, histogram, types, stats, maxima);

		// Remove points below the peak growth criteria
		pruneMaxima(image, types, searchMethod, searchParameter, stats, resultsArray, maxima);

		// Calculate the initial results (peak size and intensity)
		calculateInitialResults(image, maxima, resultsArray);

		// Calculate the highest saddle point for each peak
		ArrayList<LinkedList<double[]>> saddlePoints = new ArrayList<LinkedList<double[]>>(resultsArray.size() + 1);
		findSaddlePoints(image, types, resultsArray, maxima, saddlePoints);

		// Find the peak sizes above their saddle points.
		analysePeaks(resultsArray, image, maxima, stats);

		// TODO - Add another staging method here.

		// Combine maxima below the minimum peak criteria to adjacent peaks (or eliminate if no neighbours)
		int originalNumberOfPeaks = resultsArray.size();
		resultsArray = mergeSubPeaks(resultsArray, image, maxima, minSize, peakMethod, peakParameter, stats,
				saddlePoints, false, restrictAboveSaddle);
		if (resultsArray == null)
			return null;

		if ((options & FindFoci.OPTION_REMOVE_EDGE_MAXIMA) != 0)
			resultsArray = removeEdgeMaxima(resultsArray, maxima, stats, false);

		if (blur > 0)
		{
			// Recalculate the totals using the original image 
			calculateNativeResults(originalImage, maxima, resultsArray, originalNumberOfPeaks);
		}

		return new Object[] { resultsArray, new Integer(originalNumberOfPeaks) };
	}

	/**
	 * This method is a stripped-down version of the
	 * {@link #findMaxima(ImagePlus, int, double, String, int, double, int, int, int, double, int, int, int, double)}
	 * routine.
	 * It does not support logging, interruption or mask generation. Only the result array is generated.
	 * 
	 * <p>
	 * The method must be called with the output from
	 * {@link #findMaximaInit(ImagePlus, int, double, String, int, double, int, int, int, double, int, int, int)}
	 * 
	 * <p>
	 * This method is intended for benchmarking.
	 * 
	 * @param initArray
	 *            The output from
	 *            {@link #findMaximaInit(ImagePlus, int, double, String, int, double, int, int, int, double, int, int, int)}
	 *            Contents are destructively modified so should be cloned before input.
	 * @return Object array containing: (1) a result ArrayList<double[]> with
	 *         details of the
	 *         maxima. The details can be extracted for each result using the constants defined with the prefix
	 *         FindFoci.RESULT_;
	 *         (2) ArrayList<LinkedList<double[]>> the saddle points
	 */
	Object[] findMaximaSearch(Object[] initArray, int backgroundMethod, double backgroundParameter, int searchMethod,
			double searchParameter)
	{
		Object image = initArray[0];
		byte[] types = (byte[]) initArray[1]; // Will be a notepad for pixel types
		int[] maxima = (int[]) initArray[2]; // Contains the maxima Id assigned for each point
		Histogram histogram = (Histogram) initArray[3];
		double[] stats = (double[]) initArray[4];

		setPixels(image);
		stats[FindFoci.STATS_BACKGROUND] = getSearchThreshold(backgroundMethod, backgroundParameter, stats);
		stats[FindFoci.STATS_SUM_ABOVE_BACKGROUND] = getIntensityAboveBackground(image, types,
				stats[FindFoci.STATS_BACKGROUND]);
		Coordinate[] maxPoints = getSortedMaxPoints(image, maxima, types, (float) stats[FindFoci.STATS_MIN],
				(float) stats[FindFoci.STATS_BACKGROUND]);
		if (maxPoints == null)
			return null;

		ArrayList<double[]> resultsArray = new ArrayList<double[]>(maxPoints.length);

		assignMaxima(maxima, maxPoints, resultsArray);

		// Free memory
		maxPoints = null;

		assignPointsToMaxima(image, histogram, types, stats, maxima);

		// Remove points below the peak growth criteria
		pruneMaxima(image, types, searchMethod, searchParameter, stats, resultsArray, maxima);

		// Calculate the initial results (peak size and intensity)
		calculateInitialResults(image, maxima, resultsArray);

		// Calculate the highest saddle point for each peak
		ArrayList<LinkedList<double[]>> saddlePoints = new ArrayList<LinkedList<double[]>>(resultsArray.size() + 1);
		findSaddlePoints(image, types, resultsArray, maxima, saddlePoints);

		// Find the peak sizes above their saddle points.
		analysePeaks(resultsArray, image, maxima, stats);

		return new Object[] { resultsArray, saddlePoints };
	}

	/**
	 * This method is a stripped-down version of the
	 * {@link #findMaxima(ImagePlus, int, double, String, int, double, int, int, int, double, int, int, int, double)}
	 * routine.
	 * It does not support logging, interruption or mask generation. Only the result array is generated.
	 * 
	 * <p>
	 * The method must be called with the output from
	 * {@link #findMaximaInit(ImagePlus, int, double, String, int, double, int, int, int, double, int, int, int)}
	 * 
	 * <p>
	 * This method is intended for benchmarking.
	 * 
	 * @param initArray
	 *            The output from
	 *            {@link #findMaximaInit(ImagePlus, int, double, String, int, double, int, int, int, double, int, int, int)}
	 *            .
	 *            Contents are destructively modified so should be cloned before input.
	 * @param searchArray
	 *            The output from {@link #findMaximaSearch(Object[], int, double, int, double)}.
	 *            Contents are unchanged.
	 * @return Object array containing: (1) a result ArrayList<double[]> with
	 *         details of the
	 *         maxima. The details can be extracted for each result using the constants defined with the prefix
	 *         FindFoci.RESULT_;
	 *         (2) Integer - the original number of peaks before merging.
	 */
	Object[] findMaximaMerge(Object[] initArray, Object[] searchArray, int minSize, int peakMethod,
			double peakParameter, int options, double blur)
	{
		boolean restrictAboveSaddle = (options &
				FindFoci.OPTION_MINIMUM_ABOVE_SADDLE) == FindFoci.OPTION_MINIMUM_ABOVE_SADDLE;

		Object image = initArray[0];
		int[] maxima = (int[]) initArray[2]; // Contains the maxima Id assigned for each point
		double[] stats = (double[]) initArray[4];
		Object originalImage = initArray[5];

		@SuppressWarnings("unchecked")
		ArrayList<double[]> originalResultsArray = (ArrayList<double[]>) searchArray[0];
		@SuppressWarnings("unchecked")
		ArrayList<LinkedList<double[]>> originalSaddlePoints = (ArrayList<LinkedList<double[]>>) searchArray[1];

		// Clone the results
		ArrayList<double[]> resultsArray = new ArrayList<double[]>(originalResultsArray.size());
		for (double[] result : originalResultsArray)
			resultsArray.add(result.clone());

		// Clone the saddle points
		ArrayList<LinkedList<double[]>> saddlePoints = new ArrayList<LinkedList<double[]>>(
				originalSaddlePoints.size() + 1);
		for (LinkedList<double[]> saddlePoint : originalSaddlePoints)
		{
			LinkedList<double[]> newSaddlePoint = new LinkedList<double[]>();
			for (double[] result : saddlePoint)
			{
				newSaddlePoint.add(result.clone());
			}
			saddlePoints.add(newSaddlePoint);
		}

		// Combine maxima below the minimum peak criteria to adjacent peaks (or eliminate if no neighbours)
		int originalNumberOfPeaks = resultsArray.size();
		resultsArray = mergeSubPeaks(resultsArray, image, maxima, minSize, peakMethod, peakParameter, stats,
				saddlePoints, false, restrictAboveSaddle);
		if (resultsArray == null)
			return null;

		if ((options & FindFoci.OPTION_REMOVE_EDGE_MAXIMA) != 0)
			resultsArray = removeEdgeMaxima(resultsArray, maxima, stats, false);

		if (blur > 0)
		{
			// Recalculate the totals using the original image 
			calculateNativeResults(originalImage, maxima, resultsArray, originalNumberOfPeaks);
		}

		return new Object[] { resultsArray, new Integer(originalNumberOfPeaks) };
	}

	/**
	 * This method is a stripped-down version of the
	 * {@link #findMaxima(ImagePlus, int, double, String, int, double, int, int, int, double, int, int, int, double)}
	 * routine.
	 * It does not support logging, interruption or mask generation. Only the result array is generated.
	 * 
	 * <p>
	 * The method must be called with the output from
	 * {@link #findMaximaInit(ImagePlus, int, double, String, int, double, int, int, int, double, int, int, int)}
	 * 
	 * <p>
	 * This method is intended for benchmarking.
	 * 
	 * @param initArray
	 *            The output from
	 *            {@link #findMaximaInit(ImagePlus, int, double, String, int, double, int, int, int, double, int, int, int)}
	 *            .
	 *            Contents are destructively modified so should be cloned before input.
	 * @param runArray
	 *            The output from
	 *            {@link #findMaximaRun(Object[], int, double, int, double, int, int, double, int, int, double)}.
	 *            Contents are unchanged.
	 * @return Result containing: (1) null (this option is not supported); (2) a result ArrayList<double[]> with
	 *         details of the
	 *         maxima. The details can be extracted for each result using the constants defined with the prefix
	 *         FindFoci.RESULT_;
	 *         (3) the image statistics double[] array. The details can be extracted using the constants defined with
	 *         the FindFoci.STATS_ prefix.
	 */
	FindFociResult findMaximaResults(Object[] initArray, Object[] runArray, int maxPeaks, int sortIndex,
			int centreMethod, double centreParameter)
	{
		Object searchImage = initArray[0];
		byte[] types = (byte[]) initArray[1];
		int[] maxima = (int[]) initArray[2];
		double[] stats = (double[]) initArray[4];
		Object originalImage = initArray[5];

		@SuppressWarnings("unchecked")
		ArrayList<double[]> originalResultsArray = (ArrayList<double[]>) runArray[0];
		int originalNumberOfPeaks = (Integer) runArray[1];

		// Clone the results
		ArrayList<double[]> resultsArray = new ArrayList<double[]>(originalResultsArray.size());
		for (double[] result : originalResultsArray)
			resultsArray.add(result.clone());

		// Calculate the peaks centre and maximum value.
		locateMaxima(originalImage, searchImage, maxima, types, resultsArray, originalNumberOfPeaks, centreMethod,
				centreParameter);

		// Calculate the average intensity and values minus background
		calculateFinalResults(resultsArray, round(stats[FindFoci.STATS_BACKGROUND]));

		// Reorder the results
		sortDescResults(resultsArray, sortIndex, stats);

		// Only return the best results
		resultsArray = trim(resultsArray, maxPeaks);

		renumberPeaks(resultsArray, originalNumberOfPeaks);

		return new FindFociResult(null, resultsArray, stats);
	}

	/**
	 * This method is a stripped-down version of the
	 * {@link #findMaxima(ImagePlus, int, double, String, int, double, int, int, int, double, int, int, int, double)}
	 * routine.
	 * It does not support logging, interruption or mask generation. Only the result array is generated.
	 * 
	 * <p>
	 * The method must be called with the output from
	 * {@link #findMaximaInit(ImagePlus, int, double, String, int, double, int, int, int, double, int, int, int)}
	 * 
	 * <p>
	 * This method is intended for staged processing.
	 * 
	 * @param initArray
	 *            The output from
	 *            {@link #findMaximaInit(ImagePlus, int, double, String, int, double, int, int, int, double, int, int, int)}
	 *            .
	 *            Contents are destructively modified so should be cloned before input.
	 * @param mergeArray
	 *            The output from {@link #findMaximaMerge(Object[], Object[], int, int, double, int, double)}.
	 *            Contents are unchanged.
	 * @return Result containing: (1) null (this option is not supported); (2) a result ArrayList<double[]> with
	 *         details of the
	 *         maxima. The details can be extracted for each result using the constants defined with the prefix
	 *         FindFoci.RESULT_;
	 *         (3) the image statistics double[] array. The details can be extracted using the constants defined with
	 *         the FindFoci.STATS_ prefix.
	 */
	FindFociResult findMaximaPrelimResults(Object[] initArray, Object[] mergeArray, int maxPeaks, int sortIndex,
			int centreMethod, double centreParameter)
	{
		Object searchImage = initArray[0];
		byte[] types = (byte[]) initArray[1];
		int[] maxima = (int[]) initArray[2];
		double[] stats = (double[]) initArray[4];
		Object originalImage = initArray[5];

		@SuppressWarnings("unchecked")
		ArrayList<double[]> originalResultsArray = (ArrayList<double[]>) mergeArray[0];
		int originalNumberOfPeaks = (Integer) mergeArray[1];

		// Clone the results
		ArrayList<double[]> resultsArray = new ArrayList<double[]>(originalResultsArray.size());
		for (double[] result : originalResultsArray)
			resultsArray.add(result.clone());

		// Calculate the peaks centre and maximum value.
		locateMaxima(originalImage, searchImage, maxima, types, resultsArray, originalNumberOfPeaks, centreMethod,
				centreParameter);

		// Calculate the average intensity and values minus background
		calculateFinalResults(resultsArray, round(stats[FindFoci.STATS_BACKGROUND]));

		// Reorder the results
		sortDescResults(resultsArray, sortIndex, stats);

		// Only return the best results
		resultsArray = trim(resultsArray, maxPeaks);

		return new FindFociResult(null, resultsArray, stats);
	}

	/**
	 * This method is a stripped-down version of the
	 * {@link #findMaxima(ImagePlus, int, double, String, int, double, int, int, int, double, int, int, int, double)}
	 * routine.
	 * It does not support logging or interruption.
	 * 
	 * <p>
	 * The method must be called with the output from
	 * {@link #findMaximaPrelimResults(Object[], Object[], int, int, int, double)}
	 * 
	 * <p>
	 * This method is intended for staged processing.
	 * 
	 * @param initArray
	 *            The output from
	 *            {@link #findMaximaInit(ImagePlus, int, double, String, int, double, int, int, int, double, int, int, int)}
	 *            .
	 *            Contents are destructively modified so should be cloned before input.
	 * @param mergeArray
	 *            The output from {@link #findMaximaMerge(Object[], Object[], int, int, double, int, double)}.
	 *            Contents are unchanged.
	 * @param prelimResults
	 *            The output from {@link #findMaximaPrelimResults(Object[], Object[], int, int, int, double)}.
	 *            Contents are unchanged.
	 * @param imageTitle
	 * @param autoThresholdMethod
	 * @param fractionParameter
	 *            The height of the peak to show in the mask
	 * @return Result containing: (1) a new ImagePlus (with a stack) where the maxima are set to nMaxima+1 and
	 *         peak areas numbered starting from nMaxima (Background 0). Pixels outside of the roi of the input ip are
	 *         not set. Alternatively the peak areas can be thresholded using the auto-threshold method and coloured
	 *         1(saddle), 2(background), 3(threshold), 4(peak); (2) a result ArrayList<double[]> with details of the
	 *         maxima. The details can be extracted for each result using the constants defined with the prefix
	 *         FindFoci.RESULT_;
	 *         (3) the image statistics double[] array. The details can be extracted using the constants defined with
	 *         the FindFoci.STATS_ prefix. Returns null if cancelled by escape.
	 */
	FindFociResult findMaximaMaskResults(Object[] initArray, Object[] mergeArray, FindFociResult prelimResults,
			int outputType, String autoThresholdMethod, String imageTitle, double fractionParameter)
	{
		Object image = initArray[0];
		byte[] types = (byte[]) initArray[1];
		int[] maxima = (int[]) initArray[2];
		double[] stats = (double[]) initArray[4];

		ArrayList<double[]> originalResultsArray = prelimResults.results;
		int originalNumberOfPeaks = (Integer) mergeArray[1];

		// Clone the results
		ArrayList<double[]> resultsArray = new ArrayList<double[]>(originalResultsArray.size());
		for (double[] result : originalResultsArray)
			resultsArray.add(result.clone());

		int nMaxima = resultsArray.size();

		// Build the output mask
		ImagePlus outImp = null;
		if ((outputType & FindFoci.CREATE_OUTPUT_MASK) != 0)
		{
			ImagePlus imp = (ImagePlus) initArray[6];
			outImp = generateOutputMask(outputType, autoThresholdMethod, imp, fractionParameter, image, types, maxima,
					stats, resultsArray, nMaxima);
		}

		renumberPeaks(resultsArray, originalNumberOfPeaks);

		return new FindFociResult(outImp, resultsArray, stats);
	}

	private ImagePlus generateOutputMask(int outputType, String autoThresholdMethod, ImagePlus imp,
			double fractionParameter, Object pixels, byte[] types, int[] maxima, double[] stats,
			ArrayList<double[]> resultsArray, int nMaxima)
	{
		// TODO - Add an option for a coloured map of peaks using 4 colours. No touching peaks should be the same colour.
		// - Assign all neighbours for each cell
		// - Start @ cell with most neighbours -> label with a colour
		// - Find unlabelled cell next to labelled cell -> label with an unused colour not used by its neighbours
		// - Repeat
		// - Finish all cells with no neighbours using random colour asignment

		String imageTitle = imp.getTitle();
		// Rebuild the mask: all maxima have value 1, the remaining peak area are numbered sequentially starting
		// with value 2.
		// First create byte values to use in the mask for each maxima
		int[] maximaValues = new int[nMaxima];
		int[] maximaPeakIds = new int[nMaxima];
		float[] displayValues = new float[nMaxima];

		if ((outputType & (FindFoci.OUTPUT_MASK_ABOVE_SADDLE | FindFoci.OUTPUT_MASK_FRACTION_OF_INTENSITY |
				FindFoci.OUTPUT_MASK_FRACTION_OF_HEIGHT)) != 0)
		{
			if ((outputType & FindFoci.OUTPUT_MASK_FRACTION_OF_HEIGHT) != 0)
				fractionParameter = Math.max(Math.min(1 - fractionParameter, 1), 0);

			// Reset unneeded flags in the types array since new flags are required to mark pixels below the cut-off height.
			byte resetFlag = (byte) (SADDLE_POINT | MAX_AREA);
			for (int i = types.length; i-- > 0;)
			{
				types[i] &= resetFlag;
			}
		}
		else
		{
			// Ensure no pixels are below the saddle height
			Arrays.fill(displayValues, (float) stats[FindFoci.STATS_MIN] - 1);
		}

		setPixels(pixels);
		final boolean floatImage = pixels instanceof float[];

		for (int i = 0; i < nMaxima; i++)
		{
			maximaValues[i] = nMaxima - i;
			double[] result = resultsArray.get(i);
			maximaPeakIds[i] = (int) result[FindFoci.RESULT_PEAK_ID];
			if ((outputType & FindFoci.OUTPUT_MASK_ABOVE_SADDLE) != 0)
			{
				displayValues[i] = (float) result[FindFoci.RESULT_HIGHEST_SADDLE_VALUE];
			}
			else if ((outputType & FindFoci.OUTPUT_MASK_FRACTION_OF_HEIGHT) != 0)
			{
				displayValues[i] = (float) (fractionParameter *
						(result[FindFoci.RESULT_MAX_VALUE] - stats[FindFoci.STATS_BACKGROUND]) +
						stats[FindFoci.STATS_BACKGROUND]);
				if (!floatImage)
					displayValues[i] = (int) Math.round(displayValues[i]);
			}
		}

		if ((outputType & FindFoci.OUTPUT_MASK_FRACTION_OF_INTENSITY) != 0)
		{
			calculateFractionOfIntensityDisplayValues(fractionParameter, pixels, maxima, stats, maximaPeakIds,
					displayValues);
		}

		// Now assign the output mask
		for (int index = maxima.length; index-- > 0;)
		{
			if ((types[index] & MAX_AREA) != 0)
			{
				// Find the maxima in the list of maxima Ids.
				int i = 0;
				while (i < nMaxima && maximaPeakIds[i] != maxima[index])
					i++;
				if (i < nMaxima)
				{
					if ((getf(index) <= displayValues[i]))
						types[index] |= BELOW_SADDLE;
					maxima[index] = maximaValues[i];
					continue;
				}
			}

			// Fall through condition, reset the value
			maxima[index] = 0;
			types[index] = 0;
		}

		int maxValue = nMaxima;

		if ((outputType & FindFoci.OUTPUT_MASK_THRESHOLD) != 0)
		{
			// Perform thresholding on the peak regions
			findBorders(maxima, types);
			for (int i = 0; i < nMaxima; i++)
			{
				thresholdMask(pixels, maxima, maximaValues[i], autoThresholdMethod, stats);
			}
			invertMask(maxima);
			addBorders(maxima, types);

			// Adjust the values used to create the output mask
			maxValue = 3;
		}

		// Blank any pixels below the saddle height
		if ((outputType & (FindFoci.OUTPUT_MASK_ABOVE_SADDLE | FindFoci.OUTPUT_MASK_FRACTION_OF_INTENSITY |
				FindFoci.OUTPUT_MASK_FRACTION_OF_HEIGHT)) != 0)
		{
			for (int i = maxima.length; i-- > 0;)
			{
				if ((types[i] & BELOW_SADDLE) != 0)
					maxima[i] = 0;
			}
		}

		// Set maxima to a high value
		if ((outputType & FindFoci.OUTPUT_MASK_NO_PEAK_DOTS) == 0)
		{
			maxValue++;
			for (int i = 0; i < nMaxima; i++)
			{
				final double[] result = resultsArray.get(i);
				maxima[getIndex((int) result[FindFoci.RESULT_X], (int) result[FindFoci.RESULT_Y],
						(int) result[FindFoci.RESULT_Z])] = maxValue;
			}
		}

		// Check the maxima can be displayed
		if (maxValue > MAXIMA_CAPCITY)
		{
			IJ.log("The number of maxima exceeds the 16-bit capacity used for diplay: " + MAXIMA_CAPCITY);
			return null;
		}

		// Output the mask
		// The index is '(maxx_maxy) * z + maxx * y + x' so we can simply iterate over the array if we use z, y, x order
		ImageStack stack = new ImageStack(maxx, maxy, maxz);
		if (maxValue > 255)
		{
			for (int z = 0, index = 0; z < maxz; z++)
			{
				final short[] pixels2 = new short[maxx_maxy];
				for (int i = 0; i < maxx_maxy; i++, index++)
					pixels2[i] = (short) maxima[index];
				stack.setPixels(pixels2, z + 1);
			}
		}
		else
		{
			for (int z = 0, index = 0; z < maxz; z++)
			{
				final byte[] pixels2 = new byte[maxx_maxy];
				for (int i = 0; i < maxx_maxy; i++, index++)
					pixels2[i] = (byte) maxima[index];
				stack.setPixels(pixels2, z + 1);
			}
		}

		ImagePlus result = new ImagePlus(imageTitle + " " + FindFoci.TITLE, stack);
		result.setCalibration(imp.getCalibration());
		return result;
	}

	private void calculateFractionOfIntensityDisplayValues(double fractionParameter, Object pixels, int[] maxima,
			double[] stats, int[] maximaPeakIds, float[] displayValues)
	{
		// For each maxima
		for (int i = 0; i < maximaPeakIds.length; i++)
		{
			// Histogram all the pixels above background
			final Histogram hist = buildHistogram(pixels, maxima, maximaPeakIds[i], round(stats[FindFoci.STATS_MAX]));

			if (hist instanceof FloatHistogram)
			{
				final float background = (float) stats[FindFoci.STATS_BACKGROUND];

				// Sum above background
				double sum = 0;
				for (int bin = hist.minBin; bin <= hist.maxBin; bin++)
					sum += hist.h[bin] * (hist.getValue(bin) - background);

				// Determine the cut-off using fraction of cumulative intensity
				final double total = sum * fractionParameter;

				// Find the point in the histogram that exceeds the fraction
				sum = 0;
				int bin = hist.maxBin;
				while (bin >= hist.minBin)
				{
					sum += hist.h[bin] * (hist.getValue(bin) - background);
					if (sum > total)
						break;
					bin--;
				}

				displayValues[i] = hist.getValue(bin);
			}
			else
			{
				final int background = (int) Math.floor(stats[FindFoci.STATS_BACKGROUND]);

				// Sum above background
				long sum = 0;
				for (int bin = hist.minBin; bin <= hist.maxBin; bin++)
					sum += hist.h[bin] * (bin - background);

				// Determine the cut-off using fraction of cumulative intensity
				final long total = (long) (sum * fractionParameter);

				// Find the point in the histogram that exceeds the fraction
				sum = 0;
				int bin = hist.maxBin;
				while (bin >= hist.minBin)
				{
					sum += hist.h[bin] * (bin - background);
					if (sum > total)
						break;
					bin--;
				}

				displayValues[i] = bin;
			}
		}
	}

	/**
	 * Update the peak Ids to use the sorted order
	 */
	private void renumberPeaks(ArrayList<double[]> resultsArray, int originalNumberOfPeaks)
	{
		// Build a map between the original peak number and the new sorted order
		final int[] peakIdMap = new int[originalNumberOfPeaks + 1];
		int i = 1;
		for (double[] result : resultsArray)
		{
			peakIdMap[(int) result[FindFoci.RESULT_PEAK_ID]] = i++;
		}

		// Update the Ids
		for (double[] result : resultsArray)
		{
			result[FindFoci.RESULT_PEAK_ID] = peakIdMap[(int) result[FindFoci.RESULT_PEAK_ID]];
			result[FindFoci.RESULT_SADDLE_NEIGHBOUR_ID] = peakIdMap[(int) result[FindFoci.RESULT_SADDLE_NEIGHBOUR_ID]];
		}
	}

	/**
	 * Finds the borders of peak regions
	 * 
	 * @param maxima
	 * @param types
	 */
	private void findBorders(int[] maxima, byte[] types)
	{
		// TODO - This is not perfect. There is a problem with regions marked as saddles
		// between 3 or more peaks. This can results in large blocks of saddle regions that
		// are eroded from the outside in. In this case they should be eroded from the inside out.
		// .......Peaks..Correct..Wrong
		// .......1.22....+.+......+.+
		// ........12......+........+
		// ........12......+........+
		// .......1332....+.+.......+
		// .......3332....+..+......+
		// .......3332....+..+......+
		// (Dots inserted to prevent auto-formatting removing spaces)
		// It also only works on the XY plane. However it is fine for an approximation of the peak boundaries.

		final int[] xyz = new int[3];

		for (int index = maxima.length; index-- > 0;)
		{
			if (maxima[index] == 0)
			{
				types[index] = 0;
				continue;
			}

			// If a saddle, search around to check if it still a saddle
			if ((types[index] & SADDLE) != 0)
			{
				// reset unneeded flags
				types[index] &= BELOW_SADDLE;

				getXY(index, xyz);
				final int x = xyz[0];
				final int y = xyz[1];
				final boolean isInnerXY = (y != 0 && y != ylimit) && (x != 0 && x != xlimit);

				// Process the neighbours
				for (int d = 8; d-- > 0;)
				{
					if (isInnerXY || isWithinXY(x, y, d))
					{
						final int index2 = index + offset[d];

						// Check if neighbour is a different peak
						if (maxima[index] != maxima[index2] && maxima[index2] > 0 &&
								(types[index2] & SADDLE_POINT) != SADDLE_POINT)
						{
							types[index] |= SADDLE_POINT;
						}
					}
				}
			}

			// If it is not a saddle point then mark it as within the saddle
			if ((types[index] & SADDLE_POINT) == 0)
			{
				types[index] |= SADDLE_WITHIN;
			}
		}

		for (int z = maxz; z-- > 0;)
		{
			cleanupExtraLines(types, z);
			cleanupExtraCornerPixels(types, z);
		}
	}

	/**
	 * For each saddle pixel, check the 2 adjacent non-diagonal neighbour pixels in clockwise fashion. If they are both
	 * saddle pixels then this pixel can be removed (since they form a diagonal line).
	 */
	private int cleanupExtraCornerPixels(byte[] types, int z)
	{
		int removed = 0;
		final int[] xyz = new int[3];

		for (int i = maxx_maxy, index = maxx_maxy * z; i-- > 0; index++)
		{
			if ((types[index] & SADDLE_POINT) != 0)
			{
				getXY(index, xyz);
				final int x = xyz[0];
				final int y = xyz[1];

				final boolean isInner = (y != 0 && y != ylimit) && (x != 0 && x != xlimit);

				final boolean[] edgesSet = new boolean[8];
				for (int d = 8; d-- > 0;)
				{
					// analyze 4 flat-edge neighbours
					if (isInner || isWithinXY(x, y, d))
					{
						edgesSet[d] = ((types[index + offset[d]] & SADDLE_POINT) != 0);
					}
				}

				for (int d = 0; d < 8; d += 2)
				{
					if ((edgesSet[d] && edgesSet[(d + 2) % 8]) && !edgesSet[(d + 5) % 8])
					{
						removed++;
						types[index] &= ~SADDLE_POINT;
						types[index] |= SADDLE_WITHIN;
					}
				}
			}
		}

		return removed;
	}

	/**
	 * Delete saddle lines that do not divide two peak areas. Adapted from {@link ij.plugin.filter.MaximumFinder}
	 */
	void cleanupExtraLines(byte[] types, int z)
	{
		for (int i = maxx_maxy, index = maxx_maxy * z; i-- > 0; index++)
		{
			if ((types[index] & SADDLE_POINT) != 0)
			{
				final int nRadii = nRadii(types, index); // number of lines radiating
				if (nRadii == 0) // single point or foreground patch?
				{
					types[index] &= ~SADDLE_POINT;
					types[index] |= SADDLE_WITHIN;
				}
				else if (nRadii == 1)
					removeLineFrom(types, index);
			}
		}
	}

	/** delete a line starting at x, y up to the next (4-connected) vertex */
	void removeLineFrom(byte[] types, int index)
	{
		types[index] &= ~SADDLE_POINT;
		types[index] |= SADDLE_WITHIN;
		final int[] xyz = new int[3];
		boolean continues;
		do
		{
			getXY(index, xyz);
			final int x = xyz[0];
			final int y = xyz[1];

			continues = false;
			final boolean isInner = (y != 0 && y != maxy - 1) && (x != 0 && x != maxx - 1); // not necessary, but faster
			// than isWithin
			for (int d = 0; d < 8; d += 2)
			{ // analyze 4-connected neighbors
				if (isInner || isWithinXY(x, y, d))
				{
					int index2 = index + offset[d];
					byte v = types[index2];
					if ((v & SADDLE_WITHIN) != SADDLE_WITHIN && (v & SADDLE_POINT) == SADDLE_POINT)
					{
						int nRadii = nRadii(types, index2);
						if (nRadii <= 1)
						{ // found a point or line end
							index = index2;
							types[index] &= ~SADDLE_POINT;
							types[index] |= SADDLE_WITHIN; // delete the point
							continues = nRadii == 1; // continue along that line
							break;
						}
					}
				}
			} // for directions d
		} while (continues);
	}

	/**
	 * Analyze the neighbors of a pixel (x, y) in a byte image; pixels <255 ("non-white") are considered foreground.
	 * Edge pixels are considered foreground.
	 * 
	 * @param types
	 *            The byte image
	 * @param index
	 *            coordinate of the point
	 * @return Number of 4-connected lines emanating from this point. Zero if the point is embedded in either foreground
	 *         or background
	 */
	int nRadii(byte[] types, int index)
	{
		int countTransitions = 0;
		boolean prevPixelSet = true;
		boolean firstPixelSet = true; // initialize to make the compiler happy
		final int[] xyz = new int[3];
		getXY(index, xyz);
		final int x = xyz[0];
		final int y = xyz[1];

		final boolean isInner = (y != 0 && y != maxy - 1) && (x != 0 && x != maxx - 1); // not necessary, but faster than
		// isWithin
		for (int d = 0; d < 8; d++)
		{ // walk around the point and note every no-line->line transition
			boolean pixelSet = prevPixelSet;
			if (isInner || isWithinXY(x, y, d))
			{
				boolean isSet = ((types[index + offset[d]] & SADDLE_WITHIN) == SADDLE_WITHIN);
				if ((d & 1) == 0)
					pixelSet = isSet; // non-diagonal directions: always regarded
				else if (!isSet) // diagonal directions may separate two lines,
					pixelSet = false; // but are insufficient for a 4-connected line
			}
			else
			{
				pixelSet = true;
			}
			if (pixelSet && !prevPixelSet)
				countTransitions++;
			prevPixelSet = pixelSet;
			if (d == 0)
				firstPixelSet = pixelSet;
		}
		if (firstPixelSet && !prevPixelSet)
			countTransitions++;
		return countTransitions;
	}

	/**
	 * For each peak in the maxima image, perform thresholding using the specified method.
	 * 
	 * @param pixels
	 * @param maxima
	 * @param s
	 * @param autoThresholdMethod
	 */
	private void thresholdMask(Object pixels, int[] maxima, int peakValue, String autoThresholdMethod, double[] stats)
	{
		final Histogram histogram = buildHistogram(pixels, maxima, peakValue, (float) stats[FindFoci.STATS_MAX]);
		final float t = getThreshold(autoThresholdMethod, histogram);

		if (pixels instanceof float[])
		{
			float[] image = (float[]) pixels;
			for (int i = maxima.length; i-- > 0;)
			{
				if (maxima[i] == peakValue)
				{
					// Use negative to allow use of image in place
					maxima[i] = ((image[i] > t) ? -3 : -2);
				}
			}
		}
		else
		{
			int[] image = (int[]) pixels;
			final int threshold = (int) t;
			for (int i = maxima.length; i-- > 0;)
			{
				if (maxima[i] == peakValue)
				{
					// Use negative to allow use of image in place
					maxima[i] = ((image[i] > threshold) ? -3 : -2);
				}
			}
		}
	}

	/**
	 * Build a histogram using only the specified peak area.
	 * 
	 * @param image
	 * @param maxima
	 * @param peakValue
	 * @param maxValue
	 * @return
	 */
	protected abstract Histogram buildHistogram(Object pixels, int[] maxima, int peakValue, float maxValue);

	/**
	 * Changes all negative value to positive
	 * 
	 * @param maxima
	 */
	private void invertMask(int[] maxima)
	{
		for (int i = maxima.length; i-- > 0;)
		{
			if (maxima[i] < 0)
			{
				maxima[i] = -maxima[i];
			}
		}
	}

	/**
	 * Adds the borders to the peaks
	 * 
	 * @param maxima
	 * @param types
	 */
	private void addBorders(int[] maxima, byte[] types)
	{
		for (int i = maxima.length; i-- > 0;)
		{
			if ((types[i] & SADDLE_POINT) != 0)
			{
				maxima[i] = 1;
			}
		}
	}

	/**
	 * Records the image statistics to the log window
	 * 
	 * @param stats
	 *            The statistics
	 * @param exclusion
	 *            non-zero if pixels have been excluded
	 * @param options
	 *            The options (used to get the statistics mode)
	 */
	private void recordStatistics(double[] stats, int exclusion, int options)
	{
		StringBuilder sb = new StringBuilder();
		if (exclusion > 0)
			sb.append("Image stats (inside mask/ROI) : Min = ");
		else
			sb.append("Image stats : Min = ");
		sb.append(IJ.d2s(stats[FindFoci.STATS_MIN], 2));
		sb.append(", Max = ").append(IJ.d2s(stats[FindFoci.STATS_MAX], 2));
		sb.append(", Mean = ").append(IJ.d2s(stats[FindFoci.STATS_AV], 2));
		sb.append(", StdDev = ").append(IJ.d2s(stats[FindFoci.STATS_SD], 2));
		IJ.log(sb.toString());

		sb.setLength(0);
		if (exclusion > 0)
			sb.append("Background stats (mode=").append(FindFoci.getStatisticsMode(options)).append(") : Min = ");
		else
			sb.append("Background stats : Min = ");
		sb.append(IJ.d2s(stats[FindFoci.STATS_MIN_BACKGROUND], 2));
		sb.append(", Max = ").append(IJ.d2s(stats[FindFoci.STATS_MAX_BACKGROUND], 2));
		sb.append(", Mean = ").append(IJ.d2s(stats[FindFoci.STATS_AV_BACKGROUND], 2));
		sb.append(", StdDev = ").append(IJ.d2s(stats[FindFoci.STATS_SD_BACKGROUND], 2));
		IJ.log(sb.toString());
	}

	/**
	 * Gets the absolute height above the highest saddle or the floor, whichever is higher.
	 *
	 * @param result
	 *            the result
	 * @param floor
	 *            the floor
	 * @return the absolute height
	 */
	double getAbsoluteHeight(double[] result, double floor)
	{
		double absoluteHeight = 0;
		if (result[FindFoci.RESULT_HIGHEST_SADDLE_VALUE] > floor)
		{
			absoluteHeight = result[FindFoci.RESULT_MAX_VALUE] - result[FindFoci.RESULT_HIGHEST_SADDLE_VALUE];
		}
		else
		{
			absoluteHeight = result[FindFoci.RESULT_MAX_VALUE] - floor;
		}
		return absoluteHeight;
	}

	double getRelativeHeight(double[] result, double floor, double absoluteHeight)
	{
		return absoluteHeight / (result[FindFoci.RESULT_MAX_VALUE] - floor);
	}

	/**
	 * Set all pixels outside the ROI to PROCESSED
	 * 
	 * @param imp
	 *            The input image
	 * @param types
	 *            The types array used within the peak finding routine (same size as imp)
	 * @param isLogging
	 * @return 1 if masking was performed, else 0
	 */
	private int excludeOutsideROI(ImagePlus imp, byte[] types, boolean isLogging)
	{
		final Roi roi = imp.getRoi();

		if (roi != null && roi.isArea())
		{
			final Rectangle roiBounds = roi.getBounds();

			// Check if this ROI covers the entire image
			if (roi.getType() == Roi.RECTANGLE && roiBounds.width == maxx && roiBounds.height == maxy)
				return 0;

			// Store the bounds of the ROI for the edge object analysis
			bounds = roiBounds;

			ImageProcessor ipMask = null;
			RoundRectangle2D rr = null;

			// Use the ROI mask if present
			if (roi.getMask() != null)
			{
				ipMask = roi.getMask();
				if (isLogging)
					IJ.log("ROI = Mask");
			}
			// Use a mask for an irregular ROI
			else if (roi.getType() == Roi.FREEROI)
			{
				ipMask = imp.getMask();
				if (isLogging)
					IJ.log("ROI = Freehand ROI");
			}
			// Use a round rectangle if necessary
			else if (roi.getRoundRectArcSize() != 0)
			{
				rr = new RoundRectangle2D.Float(roiBounds.x, roiBounds.y, roiBounds.width, roiBounds.height,
						roi.getRoundRectArcSize(), roi.getRoundRectArcSize());
				if (isLogging)
					IJ.log("ROI = Round ROI");
			}

			// Set everything as processed
			for (int i = types.length; i-- > 0;)
				types[i] = EXCLUDED;

			// Now unset the ROI region

			// Create a mask from the ROI rectangle
			final int xOffset = roiBounds.x;
			final int yOffset = roiBounds.y;
			final int rwidth = roiBounds.width;
			final int rheight = roiBounds.height;

			for (int y = 0; y < rheight; y++)
			{
				for (int x = 0; x < rwidth; x++)
				{
					boolean mask = true;
					if (ipMask != null)
						mask = (ipMask.get(x, y) > 0);
					else if (rr != null)
						mask = rr.contains(x + xOffset, y + yOffset);

					if (mask)
					{
						// Set each z-slice as excluded
						for (int index = getIndex(x + xOffset, y + yOffset,
								0); index < maxx_maxy_maxz; index += maxx_maxy)
						{
							types[index] &= ~EXCLUDED;
						}
					}
				}
			}

			return 1;
		}
		return 0;
	}

	/**
	 * Set all pixels outside the Mask to PROCESSED
	 * 
	 * @param imp
	 *            The mask image
	 * @param types
	 *            The types array used within the peak finding routine
	 * @return 1 if masking was performed, else 0
	 */
	private int excludeOutsideMask(ImagePlus mask, byte[] types, boolean isLogging)
	{
		if (mask == null)
			return 0;

		// Check sizes in X & Y
		if (mask.getWidth() != maxx || mask.getHeight() != maxy ||
				(mask.getNSlices() != maxz && mask.getNSlices() != 1))
		{
			if (isLogging)
			{
				IJ.log("Mask dimensions do not match the image");
			}
			return 0;
		}

		if (isLogging)
		{
			IJ.log("Mask image = " + mask.getTitle());
		}

		if (mask.getNSlices() == 1)
		{
			// If a single plane then duplicate through the image
			final ImageProcessor ipMask = mask.getProcessor();

			for (int i = maxx_maxy; i-- > 0;)
			{
				if (ipMask.get(i) == 0)
				{
					for (int index = i; index < maxx_maxy_maxz; index += maxx_maxy)
					{
						types[index] |= EXCLUDED;
					}
				}
			}
		}
		else
		{
			// If the same stack size then process through the image
			final ImageStack stack = mask.getStack();
			final int c = mask.getChannel();
			final int f = mask.getFrame();
			for (int slice = 1; slice <= mask.getNSlices(); slice++)
			{
				int stackIndex = mask.getStackIndex(c, slice, f);
				ImageProcessor ipMask = stack.getProcessor(stackIndex);

				int index = maxx_maxy * slice;
				for (int i = maxx_maxy; i-- > 0;)
				{
					index--;
					if (ipMask.get(i) == 0)
					{
						types[index] |= EXCLUDED;
					}
				}
			}
		}

		return 1;
	}

	/**
	 * Build a histogram using all pixels not marked as EXCLUDED.
	 *
	 * @param bitDepth
	 *            the bit depth
	 * @param pixels
	 *            the image
	 * @param types
	 *            A byte image, same size as image, where the points can be marked as EXCLUDED
	 * @param statsMode
	 *            FindFoci.OPTION_STATS_INSIDE or FindFoci.OPTION_STATS_OUTSIDE
	 * @return The image histogram
	 */
	protected abstract Histogram buildHistogram(int bitDepth, Object pixels, byte[] types, int statsMode);

	/**
	 * Build a histogram using all pixels.
	 *
	 * @param bitDepth
	 *            the bit depth
	 * @param pixels
	 *            The image
	 * @return The image histogram
	 */
	protected abstract Histogram buildHistogram(int bitDepth, Object pixels);

	private double getIntensityAboveBackground(Object pixels, byte[] types, double background)
	{
		setPixels(pixels);

		final float b = (float) background;
		double sum = 0;
		for (int i = types.length; i-- > 0;)
		{
			if ((types[i] & EXCLUDED) == 0 && getf(i) > b)
			{
				sum += (getf(i) - b);
			}
		}
		return sum;
	}

	/**
	 * Return the image statistics
	 * 
	 * @param histogram
	 *            The image histogram
	 * @return Array containing: min, max, av, stdDev
	 */
	private double[] getStatistics(Histogram histogram)
	{
		// Check for an empty histogram
		if (histogram.minBin == histogram.maxBin && histogram.h[histogram.maxBin] == 0)
			return new double[11];

		// Get the average
		int count;
		double value;
		double sum = 0.0;
		double sum2 = 0.0;
		long n = 0;
		for (int i = histogram.minBin; i <= histogram.maxBin; i++)
		{
			if (histogram.h[i] != 0)
			{
				count = histogram.h[i];
				n += count;
				value = histogram.getValue(i);
				sum += value * count;
				sum2 += (value * value) * count;
			}
		}
		double av = sum / n;

		// Get the Std.Dev
		double stdDev;
		if (n > 0)
		{
			double d = n;
			stdDev = (d * sum2 - sum * sum) / d;
			if (stdDev > 0.0)
				stdDev = Math.sqrt(stdDev / (d - 1.0));
			else
				stdDev = 0.0;
		}
		else
			stdDev = 0.0;

		double min = histogram.getValue(histogram.minBin);
		double max = histogram.getValue(histogram.maxBin);
		return new double[] { min, max, av, stdDev, sum, 0, 0, min, max, av, stdDev };
	}

	/**
	 * Get the threshold for searching for maxima
	 * 
	 * @param backgroundMethod
	 *            The background thresholding method
	 * @param backgroundParameter
	 *            The method thresholding parameter
	 * @param stats
	 *            The image statistics
	 * @return The threshold
	 */
	protected abstract float getSearchThreshold(int backgroundMethod, double backgroundParameter, double[] stats);

	/**
	 * Get the minimum height for this peak above the highest saddle point
	 * 
	 * @param peakMethod
	 *            The method
	 * @param peakParameter
	 *            The method parameter
	 * @param stats
	 *            The image statistics
	 * @param v0
	 *            The current maxima value
	 * @return The minimum height
	 */
	protected abstract double getPeakHeight(int peakMethod, double peakParameter, double[] stats, double v0);

	/**
	 * Find all local maxima (irrespective whether they finally qualify as maxima or not).
	 *
	 * @param pixels
	 *            The image to be analyzed
	 * @param maxima
	 *            the maxima
	 * @param types
	 *            A byte image, same size as ip, where the maximum points are marked as MAXIMUM
	 * @param globalMin
	 *            The image global minimum
	 * @param threshold
	 *            The threshold below which no pixels are processed.
	 * @return Maxima sorted by value.
	 */
	protected Coordinate[] getSortedMaxPoints(Object pixels, int[] maxima, byte[] types, float globalMin,
			float threshold)
	{
		ArrayList<Coordinate> maxPoints = new ArrayList<Coordinate>(500);
		int[] pList = null; // working list for expanding local plateaus

		int id = 0;
		final int[] xyz = new int[3];

		//int pCount = 0;
		setPixels(pixels);
		for (int i = maxx_maxy_maxz; i-- > 0;)
		{
			if ((types[i] & (EXCLUDED | MAX_AREA | PLATEAU)) != 0)
				continue;
			final float v = getf(i);
			if (v < threshold)
				continue;
			if (v == globalMin)
				continue;

			getXYZ(i, xyz);

			final int x = xyz[0];
			final int y = xyz[1];
			final int z = xyz[2];

			/*
			 * check whether we have a local maximum.
			 */
			final boolean isInnerXY = (y != 0 && y != ylimit) && (x != 0 && x != xlimit);
			final boolean isInnerXYZ = (zlimit == 0) ? isInnerXY : isInnerXY && (z != 0 && z != zlimit);
			boolean isMax = true, equalNeighbour = false;

			// It is more likely that the z stack will be out-of-bounds.
			// Adopt the xy limit lookup and process z lookup separately

			for (int d = dStart; d-- > 0;)
			{
				if (isInnerXYZ || (isInnerXY && isWithinZ(z, d)) || isWithinXYZ(x, y, z, d))
				{
					final float vNeighbor = getf(i + offset[d]);
					if (vNeighbor > v)
					{
						isMax = false;
						break;
					}
					else if (vNeighbor == v)
					{
						// Neighbour is equal, this is a potential plateau maximum
						equalNeighbour = true;
					}
				}
			}

			if (isMax)
			{
				id++;
				if (id >= FindFoci.searchCapacity)
				{
					IJ.log("The number of potential maxima exceeds the search capacity: " + FindFoci.searchCapacity +
							". Try using a denoising/smoothing filter or increase the capacity.");
					return null;
				}

				if (equalNeighbour)
				{
					// Initialise the working list
					if (pList == null)
					{
						// Create an array to hold the rest of the points (worst case scenario for the maxima expansion)
						pList = new int[i + 1];
					}
					//pCount++;

					// Search the local area marking all equal neighbour points as maximum
					if (!expandMaximum(maxima, types, globalMin, threshold, i, v, id, maxPoints, pList))
					{
						// Not a true maximum, ignore this
						id--;
					}
				}
				else
				{
					types[i] |= MAXIMUM | MAX_AREA;
					maxima[i] = id;
					maxPoints.add(new Coordinate(x, y, z, id, v));
				}
			}
		}

		//if (pCount > 0)
		//	System.out.printf("Plateau count = %d\n", pCount);

		if (Utils.isInterrupted())
			return null;

		Collections.sort(maxPoints);

		// Build a map between the original id and the new id following the sort
		final int[] idMap = new int[maxPoints.size() + 1];

		// Label the points
		for (int i = 0; i < maxPoints.size(); i++)
		{
			final int newId = (i + 1);
			final int oldId = maxPoints.get(i).id;
			idMap[oldId] = newId;
			maxPoints.get(i).id = newId;
		}

		reassignMaxima(maxima, idMap);

		return maxPoints.toArray(new Coordinate[0]);
	} // getSortedMaxPoints

	/**
	 * Sets the pixels.
	 *
	 * @param pixels
	 *            the new pixels
	 */
	protected abstract void setPixels(Object pixels);

	/**
	 * Gets the float value of the pixels.
	 *
	 * @param i
	 *            the index
	 * @return the float value
	 */
	protected abstract float getf(int i);

	/**
	 * Searches from the specified point to find all coordinates of the same value and determines the centre of the
	 * plateau maximum.
	 *
	 * @param maxima
	 *            the maxima
	 * @param types
	 *            the types
	 * @param globalMin
	 *            the global min
	 * @param threshold
	 *            the threshold
	 * @param index0
	 *            the index0
	 * @param v0
	 *            the v0
	 * @param id
	 *            the id
	 * @param maxPoints
	 *            the max points
	 * @param pList
	 *            the list
	 * @return True if this is a true plateau, false if the plateau reaches a higher point
	 */
	private boolean expandMaximum(int[] maxima, byte[] types, float globalMin, float threshold, int index0, float v0,
			int id, ArrayList<Coordinate> maxPoints, int[] pList)
	{
		types[index0] |= LISTED | PLATEAU; // mark first point as listed
		int listI = 0; // index of current search element in the list
		int listLen = 1; // number of elements in the list

		// we create a list of connected points and start the list at the current maximum
		pList[listI] = index0;

		// Calculate the center of plateau
		boolean isPlateau = true;
		final int[] xyz = new int[3];

		do
		{
			final int index1 = pList[listI];
			getXYZ(index1, xyz);
			final int x1 = xyz[0];
			final int y1 = xyz[1];
			final int z1 = xyz[2];

			// It is more likely that the z stack will be out-of-bounds.
			// Adopt the xy limit lookup and process z lookup separately

			final boolean isInnerXY = (y1 != 0 && y1 != ylimit) && (x1 != 0 && x1 != xlimit);
			final boolean isInnerXYZ = (zlimit == 0) ? isInnerXY : isInnerXY && (z1 != 0 && z1 != zlimit);

			for (int d = dStart; d-- > 0;)
			{
				if (isInnerXYZ || (isInnerXY && isWithinZ(z1, d)) || isWithinXYZ(x1, y1, z1, d))
				{
					final int index2 = index1 + offset[d];
					if ((types[index2] & IGNORE) != 0)
					{
						// This has been done already, ignore this point
						continue;
					}

					final float v2 = getf(index2);

					if (v2 > v0)
					{
						isPlateau = false;
						//break; // Cannot break as we want to label the entire plateau.
					}
					else if (v2 == v0)
					{
						// Add this to the search
						pList[listLen++] = index2;
						types[index2] |= LISTED | PLATEAU;
					}
				}
			}

			listI++;

		} while (listI < listLen && isPlateau);

		// IJ.log("Potential plateau "+ x0 + ","+y0+","+z0+" : "+listLen);

		// Find the centre
		double xEqual = 0;
		double yEqual = 0;
		double zEqual = 0;
		int nEqual = 0;
		if (isPlateau)
		{
			for (int i = listLen; i-- > 0;)
			{
				getXYZ(pList[i], xyz);
				xEqual += xyz[0];
				yEqual += xyz[1];
				zEqual += xyz[2];
				nEqual++;
			}
		}
		xEqual /= nEqual;
		yEqual /= nEqual;
		zEqual /= nEqual;

		double dMax = Double.MAX_VALUE;
		int iMax = 0;

		// Calculate the maxima origin as the closest pixel to the centre-of-mass
		for (int i = listLen; i-- > 0;)
		{
			final int index = pList[i];
			types[index] &= ~LISTED; // reset attributes no longer needed

			if (isPlateau)
			{
				getXYZ(index, xyz);

				final int x = xyz[0];
				final int y = xyz[1];
				final int z = xyz[2];

				final double d = (xEqual - x) * (xEqual - x) + (yEqual - y) * (yEqual - y) +
						(zEqual - z) * (zEqual - z);

				if (d < dMax)
				{
					dMax = d;
					iMax = i;
				}

				types[index] |= MAX_AREA;
				maxima[index] = id;
			}
		}

		// Assign the maximum
		if (isPlateau)
		{
			final int index = pList[iMax];
			types[index] |= MAXIMUM;
			maxPoints.add(new Coordinate(index, id, v0));
		}

		return isPlateau;
	}

	/**
	 * Initialises the maxima image using the maxima Id for each point
	 * 
	 * @param maxima
	 * @param maxPoints
	 */
	private void assignMaxima(int[] maxima, Coordinate[] maxPoints, ArrayList<double[]> resultsArray)
	{
		final int[] xyz = new int[3];

		for (Coordinate maximum : maxPoints)
		{
			getXYZ(maximum.index, xyz);

			maxima[maximum.index] = maximum.id;

			final double[] result = new double[FindFoci.RESULT_LENGTH];
			result[FindFoci.RESULT_X] = xyz[0];
			result[FindFoci.RESULT_Y] = xyz[1];
			result[FindFoci.RESULT_Z] = xyz[2];
			result[FindFoci.RESULT_PEAK_ID] = maximum.id;
			result[FindFoci.RESULT_MAX_VALUE] = maximum.value;
			result[FindFoci.RESULT_INTENSITY] = maximum.value;
			result[FindFoci.RESULT_COUNT] = 1;

			resultsArray.add(result);
		}
	}

	/**
	 * Assigns points to their maxima using the steepest uphill gradient. Processes points in order of height,
	 * progressively building peaks in a top-down fashion.
	 */
	private void assignPointsToMaxima(Object pixels, Histogram hist, byte[] types, double[] stats, int[] maxima)
	{
		setPixels(pixels);
		// This is modified so clone it
		final int[] histogram = hist.h.clone();

		final int minBin = getBackgroundBin(hist, stats[FindFoci.STATS_BACKGROUND]);
		final int maxBin = hist.maxBin;

		// Create an array with the coordinates of all points between the threshold value and the max-1 value
		// (since maximum values should already have been processed)
		int arraySize = 0;
		for (int bin = minBin; bin < maxBin; bin++)
			arraySize += histogram[bin];

		if (arraySize == 0)
			return;

		final int[] coordinates = new int[arraySize]; // from pixel coordinates, low bits x, high bits y
		int highestBin = 0;
		int offset = 0;
		final int[] levelStart = new int[maxBin + 1];
		for (int bin = minBin; bin < maxBin; bin++)
		{
			levelStart[bin] = offset;
			offset += histogram[bin];
			if (histogram[bin] != 0)
				highestBin = bin;
		}
		final int[] levelOffset = new int[highestBin + 1];
		for (int i = types.length; i-- > 0;)
		{
			if ((types[i] & EXCLUDED) != 0)
				continue;

			final int v = getBin(hist, i);
			if (v >= minBin && v < maxBin)
			{
				offset = levelStart[v] + levelOffset[v];
				coordinates[offset] = i;
				levelOffset[v]++;
			}
		}

		// Process down through the levels
		int processedLevel = 0; // Counter incremented when work is done
		//int levels = 0;
		for (int level = highestBin; level >= minBin; level--)
		{
			int remaining = histogram[level];

			if (remaining == 0)
			{
				continue;
			}
			//levels++;

			// Use the idle counter to ensure that we exit the loop if no pixels have been processed for two cycles
			while (remaining > 0)
			{
				processedLevel++;
				final int n = processLevel(types, maxima, levelStart[level], remaining, coordinates, minBin);
				remaining -= n; // number of points processed

				// If nothing was done then stop
				if (n == 0)
					break;
			}

			if ((processedLevel % 64 == 0) && Utils.isInterrupted())
				return;

			if (remaining > 0 && level > minBin)
			{
				// any pixels that we have not reached?
				// It could happen if there is a large area of flat pixels => no local maxima.
				// Add to the next level.
				//IJ.log("Unprocessed " + remaining + " @level = " + level);

				int nextLevel = level; // find the next level to process
				do
					nextLevel--;
				while (nextLevel > 1 && histogram[nextLevel] == 0);

				// Add all unprocessed pixels of this level to the tasklist of the next level.
				// This could make it slow for some images, however.
				if (nextLevel > 0)
				{
					int newNextLevelEnd = levelStart[nextLevel] + histogram[nextLevel];
					for (int i = 0, p = levelStart[level]; i < remaining; i++, p++)
					{
						int index = coordinates[p];
						coordinates[newNextLevelEnd++] = index;
					}
					// tasklist for the next level to process becomes longer by this:
					histogram[nextLevel] = newNextLevelEnd - levelStart[nextLevel];
				}
			}
		}

		//int nP = 0;
		//for (byte b : types)
		//	if ((b & PLATEAU) == PLATEAU)
		//		nP++;

		//IJ.log(String.format("Processed %d levels [%d steps], %d plateau points", levels, processedLevel, nP));
	}

	/**
	 * Gets the background bin for the given background.
	 *
	 * @param histogram
	 *            the histogram
	 * @param background
	 *            the background
	 * @return the background bin
	 */
	protected abstract int getBackgroundBin(Histogram histogram, double background);

	/**
	 * Gets the bin for the given image position.
	 *
	 * @param histogram
	 *            the histogram
	 * @param i
	 *            the index
	 * @return the bin
	 */
	protected abstract int getBin(Histogram histogram, int i);

	/**
	 * Processes points in order of height, progressively building peaks in a top-down fashion.
	 * 
	 * @param types
	 *            The image pixel types
	 * @param maxima
	 *            The image maxima
	 * @param levelStart
	 *            offsets of the level in pixelPointers[]
	 * @param levelNPoints
	 *            number of points in the current level
	 * @param coordinates
	 *            list of xyz coordinates (should be offset by levelStart)
	 * @param background
	 *            The background intensity
	 * @return number of pixels that have been changed
	 */
	private int processLevel(byte[] types, int[] maxima, int levelStart, int levelNPoints, int[] coordinates,
			int background)
	{
		//int[] pList = new int[0]; // working list for expanding local plateaus
		int nChanged = 0;
		int nUnchanged = 0;
		final int[] xyz = new int[3];

		for (int i = 0, p = levelStart; i < levelNPoints; i++, p++)
		{
			final int index = coordinates[p];

			if ((types[index] & (EXCLUDED | MAX_AREA)) != 0)
			{
				// This point can be ignored
				nChanged++;
				continue;
			}

			getXYZ(index, xyz);

			// Extract the point coordinate
			final int x = xyz[0];
			final int y = xyz[1];
			final int z = xyz[2];

			final float v = getf(index);

			// It is more likely that the z stack will be out-of-bounds.
			// Adopt the xy limit lookup and process z lookup separately
			final boolean isInnerXY = (y != 0 && y != ylimit) && (x != 0 && x != xlimit);
			final boolean isInnerXYZ = (zlimit == 0) ? isInnerXY : isInnerXY && (z != 0 && z != zlimit);

			// Check for the highest neighbour

			// TODO - Try out using a Sobel operator to assign the gradient direction. Follow the steepest gradient.

			int dMax = -1;
			float vMax = v;
			for (int d = dStart; d-- > 0;)
			{
				if (isInnerXYZ || (isInnerXY && isWithinZ(z, d)) || isWithinXYZ(x, y, z, d))
				{
					final int index2 = index + offset[d];
					final float vNeighbor = getf(index2);
					if (vMax < vNeighbor) // Higher neighbour
					{
						vMax = vNeighbor;
						dMax = d;
					}
					else if (vMax == vNeighbor) // Equal neighbour
					{
						// Check if the neighbour is higher than this point (i.e. an equal higher neighbour has been found)
						if (v != vNeighbor)
						{
							// Favour flat edges over diagonals in the case of equal neighbours
							if (flatEdge[d])
							{
								dMax = d;
							}
						}
						// The neighbour is the same height, check if it is a maxima
						else if ((types[index2] & MAX_AREA) != 0)
						{
							if (dMax < 0) // Unassigned
							{
								dMax = d;
							}
							// Favour flat edges over diagonals in the case of equal neighbours
							else if (flatEdge[d])
							{
								dMax = d;
							}
						}
					}
				}
			}

			if (dMax < 0)
			{
				// This could happen if all neighbours are the same height and none are maxima.
				// Since plateau maxima should be handled in the initial maximum finding stage, any equal neighbours
				// should be processed eventually.
				coordinates[levelStart + (nUnchanged++)] = index;
				continue;
			}

			int index2 = index + offset[dMax];

			// TODO. 
			// The code below can be uncommented to flood fill a plateau with the first maxima that touches it.
			// However this can lead to striping artifacts where diagonals are at the same level but 
			// adjacent cells are not, e.g:
			// 1122
			// 1212
			// 2122
			// 1222
			// Consequently the code has been commented out and the default behaviour fills plateaus from the
			// edges inwards with a bias in the direction of the sweep across the pixels.
			// A better method may be to assign pixels to the nearest maxima using a distance measure 
			// (Euclidian, City-Block, etc). This would involve:
			// - Mark all plateau edges that touch a maxima 
			// - for each maxima edge:
			// -- Measure distance for each plateau point to the nearest touching edge
			// - Compare distance maps for each maxima and assign points to nearest maxima

			// Flood fill
			//          // A higher point has been found. Check if this position is a plateau
			//if ((types[index] & PLATEAU) == PLATEAU)
			//{
			//	IJ.log(String.format("Plateau merge to higher level: %d @ [%d,%d] : %d", image[index], x, y,
			//			image[index2]));
			//
			//	// Initialise the list to allow all points on this level to be processed. 
			//	if (pList.length < levelNPoints)
			//	{
			//		pList = new int[levelNPoints];
			//	}
			//
			//	expandPlateau(maxima, types, index, v, maxima[index2], pList);
			//}
			//else
			{
				types[index] |= MAX_AREA;
				maxima[index] = maxima[index2];
				nChanged++;
			}
		} // for pixel i

		//if (nUnchanged > 0)
		//	System.out.printf("nUnchanged = %d\n", nUnchanged);

		return nChanged;
	}// processLevel

	/**
	 * Searches from the specified point to find all coordinates of the same value and assigns them to given maximum.
	 */
	@SuppressWarnings("unused")
	private void expandPlateau(int[] maxima, byte[] types, int index0, float v0, int id, int[] pList)
	{
		types[index0] |= LISTED; // mark first point as listed
		int listI = 0; // index of current search element in the list
		int listLen = 1; // number of elements in the list

		// we create a list of connected points and start the list at the current maximum
		pList[listI] = index0;

		final int[] xyz = new int[3];

		do
		{
			final int index1 = pList[listI];
			getXYZ(index1, xyz);
			final int x1 = xyz[0];
			final int y1 = xyz[1];
			final int z1 = xyz[2];

			// It is more likely that the z stack will be out-of-bounds.
			// Adopt the xy limit lookup and process z lookup separately

			final boolean isInnerXY = (y1 != 0 && y1 != ylimit) && (x1 != 0 && x1 != xlimit);
			final boolean isInnerXYZ = (zlimit == 0) ? isInnerXY : isInnerXY && (z1 != 0 && z1 != zlimit);

			for (int d = dStart; d-- > 0;)
			{
				if (isInnerXYZ || (isInnerXY && isWithinZ(z1, d)) || isWithinXYZ(x1, y1, z1, d))
				{
					final int index2 = index1 + offset[d];
					if ((types[index2] & IGNORE) != 0)
					{
						// This has been done already, ignore this point
						continue;
					}

					final float v2 = getf(index2);

					if (v2 == v0)
					{
						// Add this to the search
						pList[listLen++] = index2;
						types[index2] |= LISTED;
					}
				}
			}

			listI++;

		} while (listI < listLen);

		//IJ.log("Plateau size = "+listLen);

		for (int i = listLen; i-- > 0;)
		{
			final int index = pList[i];
			types[index] &= ~LISTED; // reset attributes no longer needed

			// Assign to the given maximum
			types[index] |= MAX_AREA;
			maxima[index] = id;
		}
	}

	/**
	 * Loop over all points that have been assigned to a peak area and clear any pixels below the peak growth threshold
	 * 
	 * @param image
	 * @param roiBounds
	 * @param types
	 * @param maxPoints
	 * @param searchMethod
	 * @param searchParameter
	 * @param stats
	 * @param resultsArray
	 * @param maxima
	 */
	private void pruneMaxima(Object pixels, byte[] types, int searchMethod, double searchParameter, double[] stats,
			ArrayList<double[]> resultsArray, int[] maxima)
	{
		setPixels(pixels);

		// Build an array containing the threshold for each peak.
		// Note that maxima are numbered from 1
		final int nMaxima = resultsArray.size();
		final float[] peakThreshold = new float[nMaxima + 1];
		for (int i = 1; i < peakThreshold.length; i++)
		{
			final double v0 = resultsArray.get(i - 1)[FindFoci.RESULT_MAX_VALUE];
			peakThreshold[i] = getTolerance(searchMethod, searchParameter, stats, v0);
		}

		for (int i = maxima.length; i-- > 0;)
		{
			if (maxima[i] > 0)
			{
				if (getf(i) < peakThreshold[maxima[i]])
				{
					// Unset this pixel as part of the peak
					maxima[i] = 0;
					types[i] &= ~MAX_AREA;
				}
			}
		}
	}

	/**
	 * Get the threshold that limits the maxima region growing
	 * 
	 * @param searchMethod
	 *            The thresholding method
	 * @param searchParameter
	 *            The method thresholding parameter
	 * @param stats
	 *            The image statistics
	 * @param v0
	 *            The current maxima value
	 * @return The threshold
	 */
	protected abstract float getTolerance(int searchMethod, double searchParameter, double[] stats, double v0);

	protected int round(double d)
	{
		return (int) Math.round(d);
	}

	/**
	 * Loop over the image and sum the intensity and size of each peak area, storing this into the results array
	 * 
	 * @param image
	 * @param roi
	 * @param maxima
	 * @param resultsArray
	 */
	private void calculateInitialResults(Object pixels, int[] maxima, ArrayList<double[]> resultsArray)
	{
		setPixels(pixels);
		final int nMaxima = resultsArray.size();

		// Maxima are numbered from 1
		final int[] count = new int[nMaxima + 1];
		final double[] intensity = new double[nMaxima + 1];

		for (int i = maxima.length; i-- > 0;)
		{
			if (maxima[i] > 0)
			{
				count[maxima[i]]++;
				intensity[maxima[i]] += getf(i);
			}
		}

		for (double[] result : resultsArray)
		{
			result[FindFoci.RESULT_COUNT] = count[(int) result[FindFoci.RESULT_PEAK_ID]];
			result[FindFoci.RESULT_INTENSITY] = intensity[(int) result[FindFoci.RESULT_PEAK_ID]];
			result[FindFoci.RESULT_AVERAGE_INTENSITY] = result[FindFoci.RESULT_INTENSITY] /
					result[FindFoci.RESULT_COUNT];
		}
	}

	/**
	 * Loop over the image and sum the intensity of each peak area using the original image, storing this into the
	 * results array.
	 */
	private void calculateNativeResults(Object pixels, int[] maxima, ArrayList<double[]> resultsArray,
			int originalNumberOfPeaks)
	{
		setPixels(pixels);

		// Maxima are numbered from 1
		final double[] intensity = new double[originalNumberOfPeaks + 1];
		final float[] max = new float[originalNumberOfPeaks + 1];

		for (int i = maxima.length; i-- > 0;)
		{
			if (maxima[i] > 0)
			{
				final float v = getf(i);
				intensity[maxima[i]] += v;
				if (max[maxima[i]] < v)
					max[maxima[i]] = v;
			}
		}

		for (double[] result : resultsArray)
		{
			final int id = (int) result[FindFoci.RESULT_PEAK_ID];
			if (intensity[id] > 0)
			{
				result[FindFoci.RESULT_INTENSITY] = intensity[id];
				result[FindFoci.RESULT_MAX_VALUE] = max[id];
			}
		}
	}

	/**
	 * Calculate the peaks centre and maximum value. This could be done in many ways:
	 * - Max value
	 * - Centre-of-mass (within a bounding box of max value defined by the centreParameter)
	 * - Gaussian fit (Using a 2D projection defined by the centreParameter: (1) Maximum value; (other) Average value)
	 * 
	 * @param image
	 * @param maxima
	 * @param types
	 * @param resultsArray
	 * @param originalNumberOfPeaks
	 * @param centreMethod
	 * @param centreParameter
	 */
	private void locateMaxima(Object pixels, Object searchPixels, int[] maxima, byte[] types,
			ArrayList<double[]> resultsArray, int originalNumberOfPeaks, int centreMethod, double centreParameter)
	{
		if (centreMethod == FindFoci.CENTRE_MAX_VALUE_SEARCH)
		{
			return; // This is the current value so just return
		}

		// Swap to the search image for processing if necessary
		switch (centreMethod)
		{
			case FindFoci.CENTRE_GAUSSIAN_SEARCH:
			case FindFoci.CENTRE_OF_MASS_SEARCH:
			case FindFoci.CENTRE_MAX_VALUE_SEARCH:
				pixels = searchPixels;
		}

		setPixels(pixels);

		// Working list of peak coordinates 
		int[] pList = new int[0];

		// For each peak, compute the centre
		for (double[] result : resultsArray)
		{
			// Ensure list is large enough
			if (pList.length < (int) result[FindFoci.RESULT_COUNT])
				pList = new int[(int) result[FindFoci.RESULT_COUNT]];

			// Find the peak coords above the saddle
			final int maximaId = (int) result[FindFoci.RESULT_PEAK_ID];
			final int index = getIndex((int) result[FindFoci.RESULT_X], (int) result[FindFoci.RESULT_Y],
					(int) result[FindFoci.RESULT_Z]);
			final int listLen = findMaximaCoords(maxima, types, index, maximaId,
					(float) result[FindFoci.RESULT_HIGHEST_SADDLE_VALUE], pList);
			//IJ.log("maxima size > saddle = " + listLen);

			// Find the boundaries of the coordinates
			final int[] min_xyz = new int[] { maxx, maxy, maxz };
			final int[] max_xyz = new int[] { 0, 0, 0 };
			final int[] xyz = new int[3];
			for (int listI = listLen; listI-- > 0;)
			{
				final int index1 = pList[listI];
				getXYZ(index1, xyz);
				for (int i = 3; i-- > 0;)
				{
					if (min_xyz[i] > xyz[i])
						min_xyz[i] = xyz[i];
					if (max_xyz[i] < xyz[i])
						max_xyz[i] = xyz[i];
				}
			}
			//IJ.log("Boundaries " + maximaId + " : " + min_xyz[0] + "," + min_xyz[1] + "," + min_xyz[2] + " => " +
			//		max_xyz[0] + "," + max_xyz[1] + "," + max_xyz[2]);

			// Extract sub image
			final int[] dimensions = new int[3];
			for (int i = 3; i-- > 0;)
				dimensions[i] = max_xyz[i] - min_xyz[i] + 1;

			final float[] subImage = extractSubImage(maxima, min_xyz, dimensions, maximaId,
					(float) result[FindFoci.RESULT_HIGHEST_SADDLE_VALUE]);

			int[] centre = null;
			switch (centreMethod)
			{
				case FindFoci.CENTRE_GAUSSIAN_SEARCH:
				case FindFoci.CENTRE_GAUSSIAN_ORIGINAL:
					centre = findCentreGaussianFit(subImage, dimensions, round(centreParameter));
					//					if (centre == null)
					//					{
					//						if (IJ.debugMode)
					//						{
					//							IJ.log("No Gaussian fit");
					//						}
					//					}
					break;

				case FindFoci.CENTRE_OF_MASS_SEARCH:
				case FindFoci.CENTRE_OF_MASS_ORIGINAL:
					centre = findCentreOfMass(subImage, dimensions, round(centreParameter));
					break;

				case FindFoci.CENTRE_MAX_VALUE_ORIGINAL:
				default:
					centre = findCentreMaxValue(subImage, dimensions);
			}

			if (centre != null)
			{
				if (IJ.debugMode)
				{
					final int[] shift = new int[3];
					double d = 0;
					for (int i = 3; i-- > 0;)
					{
						shift[i] = (int) result[i] - (centre[i] + min_xyz[i]);
						d += shift[i] * shift[i];
					}
					IJ.log("Moved centre: " + shift[0] + " , " + shift[1] + " , " + shift[2] + " = " +
							IJ.d2s(Math.sqrt(d), 2));
				}

				// FindFoci.RESULT_[XYZ] are 0, 1, 2
				for (int i = 3; i-- > 0;)
					result[i] = centre[i] + min_xyz[i];
			}
		}
	}

	/**
	 * Search for all connected points in the maxima above the saddle value.
	 * 
	 * @return The number of points
	 */
	private int findMaximaCoords(int[] maxima, byte[] types, int index0, int maximaId, float saddleValue, int[] pList)
	{
		types[index0] |= LISTED; // mark first point as listed
		int listI = 0; // index of current search element in the list
		int listLen = 1; // number of elements in the list

		// we create a list of connected points and start the list at the current maximum
		pList[listI] = index0;

		final int[] xyz = new int[3];

		do
		{
			final int index1 = pList[listI];
			getXYZ(index1, xyz);
			final int x1 = xyz[0];
			final int y1 = xyz[1];
			final int z1 = xyz[2];

			final boolean isInnerXY = (y1 != 0 && y1 != ylimit) && (x1 != 0 && x1 != xlimit);
			final boolean isInnerXYZ = (zlimit == 0) ? isInnerXY : isInnerXY && (z1 != 0 && z1 != zlimit);

			for (int d = dStart; d-- > 0;)
			{
				if (isInnerXYZ || (isInnerXY && isWithinZ(z1, d)) || isWithinXYZ(x1, y1, z1, d))
				{
					final int index2 = index1 + offset[d];
					if ((types[index2] & IGNORE) != 0 || maxima[index2] != maximaId)
					{
						// This has been done already, ignore this point
						continue;
					}

					final float v2 = getf(index2);

					if (v2 >= saddleValue)
					{
						// Add this to the search
						pList[listLen++] = index2;
						types[index2] |= LISTED;
					}
				}
			}

			listI++;

		} while (listI < listLen);

		for (int i = listLen; i-- > 0;)
		{
			final int index = pList[i];
			types[index] &= ~LISTED; // reset attributes no longer needed
		}

		return listLen;
	}

	/**
	 * Extract a sub-image from the given input image using the specified boundaries. The minValue is subtracted from
	 * all pixels. All pixels below the minValue are ignored (set to zero).
	 * 
	 * @param result
	 * @param maxima
	 */
	private float[] extractSubImage(int[] maxima, int[] min_xyz, int[] dimensions, int maximaId, float minValue)
	{
		final float[] subImage = new float[dimensions[0] * dimensions[1] * dimensions[2]];

		int offset = 0;
		for (int z = 0; z < dimensions[2]; z++)
		{
			for (int y = 0; y < dimensions[1]; y++)
			{
				int index = getIndex(min_xyz[0], y + min_xyz[1], z + min_xyz[2]);
				for (int x = 0; x < dimensions[0]; x++, index++, offset++)
				{
					if (maxima[index] == maximaId && getf(index) > minValue)
						subImage[offset] = getf(index) - minValue;
				}
			}
		}

		// DEBUGGING
		//		ImageProcessor ip = new ShortProcessor(dimensions[0], dimensions[1]);
		//		for (int i = subImage.length; i-- > 0;)
		//			ip.set(i, subImage[i]);
		//		new ImagePlus(null, ip).show();

		return subImage;
	}

	/**
	 * Finds the centre of the image using the maximum pixel value.
	 * If many pixels have the same value the closest pixel to the geometric mean of the coordinates is returned.
	 */
	private int[] findCentreMaxValue(float[] image, int[] dimensions)
	{
		// Find the maximum value in the image
		float maxValue = 0;
		int count = 0;
		int index = 0;
		for (int i = image.length; i-- > 0;)
		{
			if (maxValue < image[i])
			{
				maxValue = image[i];
				index = i;
				count = 1;
			}
			else if (maxValue == image[i])
			{
				count++;
			}
		}

		// Used to map index back to XYZ
		final int blockSize = dimensions[0] * dimensions[1];

		if (count == 1)
		{
			// There is only one maximum pixel
			final int[] xyz = new int[3];
			xyz[2] = index / (blockSize);
			final int mod = index % (blockSize);
			xyz[1] = mod / dimensions[0];
			xyz[0] = mod % dimensions[0];
			return xyz;
		}

		// Find geometric mean
		final double[] centre = new double[3];
		for (int i = image.length; i-- > 0;)
		{
			if (maxValue == image[i])
			{
				final int[] xyz = new int[3];
				xyz[2] = i / (blockSize);
				final int mod = i % (blockSize);
				xyz[1] = mod / dimensions[0];
				xyz[0] = mod % dimensions[0];
				for (int j = 3; j-- > 0;)
					centre[j] += xyz[j];
			}
		}
		for (int j = 3; j-- > 0;)
			centre[j] /= count;

		// Find nearest point
		double dMin = Double.MAX_VALUE;
		int[] closest = new int[] { round(centre[0]), round(centre[1]), round(centre[2]) };
		for (int i = image.length; i-- > 0;)
		{
			if (maxValue == image[i])
			{
				final int[] xyz = new int[3];
				xyz[2] = i / (blockSize);
				final int mod = i % (blockSize);
				xyz[1] = mod / dimensions[0];
				xyz[0] = mod % dimensions[0];
				final double d = Math.pow(xyz[0] - centre[0], 2) + Math.pow(xyz[1] - centre[1], 2) +
						Math.pow(xyz[2] - centre[2], 2);
				if (dMin > d)
				{
					dMin = d;
					closest = xyz;
				}
			}
		}

		return closest;
	}

	/**
	 * Finds the centre of the image using the centre of mass within the given range of the maximum pixel value.
	 */
	private int[] findCentreOfMass(float[] subImage, int[] dimensions, int range)
	{
		final int[] centre = findCentreMaxValue(subImage, dimensions);
		double[] com = new double[] { centre[0], centre[1], centre[2] };

		// Iterate until convergence
		double distance;
		int iter = 0;
		do
		{
			final double[] newCom = findCentreOfMass(subImage, dimensions, range, com);
			distance = Math.pow(newCom[0] - com[0], 2) + Math.pow(newCom[1] - com[1], 2) +
					Math.pow(newCom[2] - com[2], 2);
			com = newCom;
			iter++;
		} while (distance > 1 && iter < 10);

		return convertCentre(com);
	}

	/**
	 * Finds the centre of the image using the centre of mass within the given range of the specified centre-of-mass.
	 */
	private double[] findCentreOfMass(float[] subImage, int[] dimensions, int range, double[] com)
	{
		final int[] centre = convertCentre(com);

		final int[] min = new int[3];
		final int[] max = new int[3];
		if (range < 1)
			range = 1;
		for (int i = 3; i-- > 0;)
		{
			min[i] = centre[i] - range;
			max[i] = centre[i] + range;
			if (min[i] < 0)
				min[i] = 0;
			if (max[i] >= dimensions[i] - 1)
				max[i] = dimensions[i] - 1;
		}

		final int blockSize = dimensions[0] * dimensions[1];

		final double[] newCom = new double[3];
		double sum = 0;
		for (int z = min[2]; z <= max[2]; z++)
		{
			for (int y = min[1]; y <= max[1]; y++)
			{
				int index = blockSize * z + dimensions[0] * y + min[0];
				for (int x = min[0]; x <= max[0]; x++, index++)
				{
					final float value = subImage[index];
					if (value > 0)
					{
						sum += value;
						newCom[0] += x * value;
						newCom[1] += y * value;
						newCom[2] += z * value;
					}
				}
			}
		}

		for (int i = 3; i-- > 0;)
		{
			newCom[i] /= sum;
		}

		return newCom;
	}

	/**
	 * Finds the centre of the image using a 2D Gaussian fit to projection along the Z-axis.
	 * 
	 * @param subImage
	 * @param dimensions
	 * @param projectionMethod
	 *            (0) Average value; (1) Maximum value
	 * @return
	 */
	private int[] findCentreGaussianFit(float[] subImage, int[] dimensions, int projectionMethod)
	{
		if (FindFoci.isGaussianFitEnabled < 1)
			return null;

		final int blockSize = dimensions[0] * dimensions[1];
		final float[] projection = new float[blockSize];

		if (projectionMethod == 1)
		{
			// Maximum value
			for (int z = dimensions[2]; z-- > 0;)
			{
				int index = blockSize * z;
				for (int i = 0; i < blockSize; i++, index++)
				{
					if (projection[i] < subImage[index])
						projection[i] = subImage[index];
				}
			}
		}
		else
		{
			// Average value
			for (int z = dimensions[2]; z-- > 0;)
			{
				int index = blockSize * z;
				for (int i = 0; i < blockSize; i++, index++)
					projection[i] += subImage[index];
			}
			for (int i = blockSize; i-- > 0;)
				projection[i] /= dimensions[2];
		}

		final GaussianFit gf = new GaussianFit();
		final double[] fitParams = gf.fit(projection, dimensions[0], dimensions[1]);

		int[] centre = null;
		if (fitParams != null)
		{
			// Find the centre of mass along the z-axis
			centre = convertCentre(new double[] { fitParams[2], fitParams[3] });

			// Use the centre of mass along the projection axis
			double com = 0;
			long sum = 0;
			for (int z = dimensions[2]; z-- > 0;)
			{
				final int index = blockSize * z;
				final float value = subImage[index];
				if (value > 0)
				{
					com += z * value;
					sum += value;
				}
			}
			centre[2] = round(com / sum);
			// Avoid clipping
			if (centre[2] < 0)
				centre[2] = 0;
			if (centre[2] >= dimensions[2])
				centre[2] = dimensions[2] - 1;
		}

		return centre;
	}

	/**
	 * Convert the centre from double to int. Handles input arrays of length 2 or 3.
	 */
	private int[] convertCentre(double[] centre)
	{
		int[] newCentre = new int[3];
		for (int i = centre.length; i-- > 0;)
		{
			newCentre[i] = round(centre[i]);
		}
		return newCentre;
	}

	/**
	 * Loop over the results array and calculate the average intensity and the intensity above the background
	 * 
	 * @param resultsArray
	 * @param background
	 */
	private void calculateFinalResults(ArrayList<double[]> resultsArray, double background)
	{
		for (double[] result : resultsArray)
		{
			result[FindFoci.RESULT_INTENSITY_MINUS_BACKGROUND] = result[FindFoci.RESULT_INTENSITY] -
					background * result[FindFoci.RESULT_COUNT];
			result[FindFoci.RESULT_AVERAGE_INTENSITY] = result[FindFoci.RESULT_INTENSITY] /
					result[FindFoci.RESULT_COUNT];
			result[FindFoci.RESULT_AVERAGE_INTENSITY_MINUS_BACKGROUND] = result[FindFoci.RESULT_INTENSITY_MINUS_BACKGROUND] /
					result[FindFoci.RESULT_COUNT];
		}
	}

	/**
	 * Finds the highest saddle point for each peak.
	 *
	 * @param pixels
	 *            the pixels
	 * @param types
	 *            the types
	 * @param resultsArray
	 *            the results array
	 * @param maxima
	 *            the maxima
	 * @param saddlePoints
	 *            Contains an entry for each peak indexed from 1. The entry is a linked list of saddle points. Each
	 *            saddle point is an array containing the neighbouring peak ID and the saddle value.
	 */
	private void findSaddlePoints(Object pixels, byte[] types, ArrayList<double[]> resultsArray, int[] maxima,
			ArrayList<LinkedList<double[]>> saddlePoints)
	{
		setPixels(pixels);

		// Initialise the saddle points
		final int nMaxima = resultsArray.size();
		for (int i = 0; i < nMaxima + 1; i++)
			saddlePoints.add(new LinkedList<double[]>());

		final int maxPeakSize = getMaxPeakSize(resultsArray);
		final int[] pListI = new int[maxPeakSize]; // here we enter points starting from a maximum (index,value)
		final float[] pListV = new float[maxPeakSize];
		final int[] xyz = new int[3];

		/* Process all the maxima */
		for (double[] result : resultsArray)
		{
			final int x0 = (int) result[FindFoci.RESULT_X];
			final int y0 = (int) result[FindFoci.RESULT_Y];
			final int z0 = (int) result[FindFoci.RESULT_Z];
			final int id = (int) result[FindFoci.RESULT_PEAK_ID];
			final int index0 = getIndex(x0, y0, z0);

			// List of saddle highest values with every other peak
			final float[] highestSaddleValue = new float[nMaxima + 1];

			types[index0] |= LISTED; // mark first point as listed
			int listI = 0; // index of current search element in the list
			int listLen = 1; // number of elements in the list

			// we create a list of connected points and start the list at the current maximum
			pListI[0] = index0;
			pListV[0] = getf(index0);

			do
			{
				final int index1 = pListI[listI];
				final float v1 = pListV[listI];

				getXYZ(index1, xyz);
				final int x1 = xyz[0];
				final int y1 = xyz[1];
				final int z1 = xyz[2];

				// It is more likely that the z stack will be out-of-bounds.
				// Adopt the xy limit lookup and process z lookup separately

				final boolean isInnerXY = (y1 != 0 && y1 != ylimit) && (x1 != 0 && x1 != xlimit);
				final boolean isInnerXYZ = (zlimit == 0) ? isInnerXY : isInnerXY && (z1 != 0 && z1 != zlimit);

				// Check for the highest neighbour
				for (int d = dStart; d-- > 0;)
				{
					if (isInnerXYZ || (isInnerXY && isWithinZ(z1, d)) || isWithinXYZ(x1, y1, z1, d))
					{
						// Get the coords
						final int index2 = index1 + offset[d];

						if ((types[index2] & IGNORE) != 0)
						{
							// This has been done already, ignore this point
							continue;
						}

						final int id2 = maxima[index2];

						if (id2 == id)
						{
							// Add this to the search
							pListI[listLen] = index2;
							pListV[listLen] = getf(index2);
							listLen++;
							types[index2] |= LISTED;
						}
						else if (id2 != 0)
						{
							// This is another peak, see if it a saddle highpoint
							final float v2 = getf(index2);

							// Take the lower of the two points as the saddle
							final float minV;
							if (v1 < v2)
							{
								types[index1] |= SADDLE;
								minV = v1;
							}
							else
							{
								types[index2] |= SADDLE;
								minV = v2;
							}

							if (highestSaddleValue[id2] < minV)
							{
								highestSaddleValue[id2] = minV;
							}
						}
					}
				}

				listI++;

			} while (listI < listLen);

			for (int i = listLen; i-- > 0;)
			{
				final int index = pListI[i];
				types[index] &= ~LISTED; // reset attributes no longer needed
			}

			// Find the highest saddle
			int highestNeighbourPeakId = 0;
			float highestNeighbourValue = 0;
			LinkedList<double[]> saddles = saddlePoints.get(id);
			for (int id2 = 1; id2 <= nMaxima; id2++)
			{
				if (highestSaddleValue[id2] > 0)
				{
					saddles.add(new double[] { id2, highestSaddleValue[id2] });
					// IJ.log("Peak saddle " + id + " -> " + id2 + " @ " + highestSaddleValue[id2]);
					if (highestNeighbourValue < highestSaddleValue[id2])
					{
						highestNeighbourValue = highestSaddleValue[id2];
						highestNeighbourPeakId = id2;
					}
				}
			}

			// Set the saddle point
			if (highestNeighbourPeakId > 0)
			{
				result[FindFoci.RESULT_SADDLE_NEIGHBOUR_ID] = highestNeighbourPeakId;
				result[FindFoci.RESULT_HIGHEST_SADDLE_VALUE] = highestNeighbourValue;
			}
		} // for all maxima
	}

	private int getMaxPeakSize(ArrayList<double[]> resultsArray)
	{
		double maxPeakSize = 0;
		for (double[] result : resultsArray)
		{
			if (maxPeakSize < result[FindFoci.RESULT_COUNT])
				maxPeakSize = result[FindFoci.RESULT_COUNT];
		}
		return (int) maxPeakSize;
	}

	/**
	 * Find the size and intensity of peaks above their saddle heights.
	 */
	private void analysePeaks(ArrayList<double[]> resultsArray, Object pixels, int[] maxima, double[] stats)
	{
		setPixels(pixels);

		// Create an array of the size/intensity of each peak above the highest saddle 
		double[] peakIntensity = new double[resultsArray.size() + 1];
		int[] peakSize = new int[resultsArray.size() + 1];

		// Store all the saddle heights
		float[] saddleHeight = new float[resultsArray.size() + 1];
		for (double[] result : resultsArray)
		{
			saddleHeight[(int) result[FindFoci.RESULT_PEAK_ID]] = (float) result[FindFoci.RESULT_HIGHEST_SADDLE_VALUE];
		}

		for (int i = maxima.length; i-- > 0;)
		{
			if (maxima[i] > 0)
			{
				if (getf(i) > saddleHeight[maxima[i]])
				{
					peakIntensity[maxima[i]] += getf(i);
					peakSize[maxima[i]]++;
				}
			}
		}

		for (double[] result : resultsArray)
		{
			result[FindFoci.RESULT_COUNT_ABOVE_SADDLE] = peakSize[(int) result[FindFoci.RESULT_PEAK_ID]];
			result[FindFoci.RESULT_INTENSITY_ABOVE_SADDLE] = peakIntensity[(int) result[FindFoci.RESULT_PEAK_ID]];
		}
	}

	/**
	 * Merge sub-peaks into their highest neighbour peak using the highest saddle point
	 */
	private ArrayList<double[]> mergeSubPeaks(ArrayList<double[]> resultsArray, Object pixels, int[] maxima,
			int minSize, int peakMethod, double peakParameter, double[] stats,
			ArrayList<LinkedList<double[]>> saddlePoints, boolean isLogging, boolean restrictAboveSaddle)
	{
		setPixels(pixels);

		// Create an array containing the mapping between the original peak Id and the current Id that the peak has been
		// mapped to.
		final int[] peakIdMap = new int[resultsArray.size() + 1];
		for (int i = 0; i < peakIdMap.length; i++)
			peakIdMap[i] = i;

		// Process all the peaks for the minimum height. Process in order of saddle height
		sortDescResults(resultsArray, FindFoci.SORT_SADDLE_HEIGHT, stats);

		for (double[] result : resultsArray)
		{
			final int peakId = (int) result[FindFoci.RESULT_PEAK_ID];
			final LinkedList<double[]> saddles = saddlePoints.get(peakId);

			// Check if this peak has been reassigned or has no neighbours
			if (peakId != peakIdMap[peakId])
				continue;

			final double[] highestSaddle = findHighestNeighbourSaddle(peakIdMap, saddles, peakId);

			final double peakBase = (highestSaddle == null) ? stats[FindFoci.STATS_BACKGROUND] : highestSaddle[1];

			final double threshold = getPeakHeight(peakMethod, peakParameter, stats, result[FindFoci.RESULT_MAX_VALUE]);

			if (result[FindFoci.RESULT_MAX_VALUE] - peakBase < threshold)
			{
				// This peak is not high enough, merge into the neighbour peak
				if (highestSaddle == null)
				{
					removePeak(maxima, peakIdMap, result, peakId);
				}
				else
				{
					// Find the neighbour peak (use the map because the neighbour may have been merged)
					final int neighbourPeakId = peakIdMap[(int) highestSaddle[SADDLE_PEAK_ID]];
					final double[] neighbourResult = findResult(resultsArray, neighbourPeakId);

					mergePeak(maxima, peakIdMap, peakId, result, neighbourPeakId, neighbourResult, saddles,
							saddlePoints.get(neighbourPeakId), highestSaddle, false);
				}
			}
		}

		if (isLogging)
			IJ.log("Height filter : Number of peaks = " + countPeaks(peakIdMap));
		if (Utils.isInterrupted())
			return null;

		// Process all the peaks for the minimum size. Process in order of smallest first
		sortAscResults(resultsArray, FindFoci.SORT_COUNT, stats);

		for (double[] result : resultsArray)
		{
			final int peakId = (int) result[FindFoci.RESULT_PEAK_ID];

			// Check if this peak has been reassigned
			if (peakId != peakIdMap[peakId])
				continue;

			if (result[FindFoci.RESULT_COUNT] < minSize)
			{
				// This peak is not large enough, merge into the neighbour peak

				final LinkedList<double[]> saddles = saddlePoints.get(peakId);
				final double[] highestSaddle = findHighestNeighbourSaddle(peakIdMap, saddles, peakId);

				if (highestSaddle == null)
				{
					removePeak(maxima, peakIdMap, result, peakId);
				}
				else
				{
					// Find the neighbour peak (use the map because the neighbour may have been merged)
					final int neighbourPeakId = peakIdMap[(int) highestSaddle[SADDLE_PEAK_ID]];
					final double[] neighbourResult = findResult(resultsArray, neighbourPeakId);

					mergePeak(maxima, peakIdMap, peakId, result, neighbourPeakId, neighbourResult, saddles,
							saddlePoints.get(neighbourPeakId), highestSaddle, false);
				}
			}
		}

		if (isLogging)
			IJ.log("Size filter : Number of peaks = " + countPeaks(peakIdMap));
		if (Utils.isInterrupted())
			return null;

		// This can be intensive due to the requirement to recount the peak size above the saddle, so it is optional
		if (restrictAboveSaddle)
		{
			updateSaddleDetails(resultsArray, peakIdMap);
			reassignMaxima(maxima, peakIdMap);
			analysePeaks(resultsArray, pixels, maxima, stats);

			// Process all the peaks for the minimum size above the saddle points. Process in order of smallest first
			sortAscResults(resultsArray, FindFoci.SORT_COUNT_ABOVE_SADDLE, stats);

			for (double[] result : resultsArray)
			{
				final int peakId = (int) result[FindFoci.RESULT_PEAK_ID];

				// Check if this peak has been reassigned
				if (peakId != peakIdMap[peakId])
					continue;

				if (result[FindFoci.RESULT_COUNT_ABOVE_SADDLE] < minSize)
				{
					// This peak is not large enough, merge into the neighbour peak

					final LinkedList<double[]> saddles = saddlePoints.get(peakId);
					final double[] highestSaddle = findHighestNeighbourSaddle(peakIdMap, saddles, peakId);

					if (highestSaddle == null)
					{
						// TODO - This should not occur... ? What is the count above the saddle?

						// No neighbour so just remove
						mergePeak(maxima, peakIdMap, peakId, result, 0, null, null, null, null, true);
					}
					else
					{
						// Find the neighbour peak (use the map because the neighbour may have been merged)
						final int neighbourPeakId = peakIdMap[(int) highestSaddle[SADDLE_PEAK_ID]];
						final double[] neighbourResult = findResult(resultsArray, neighbourPeakId);

						// Note: Ensure the peak counts above the saddle are updated.
						mergePeak(maxima, peakIdMap, peakId, result, neighbourPeakId, neighbourResult, saddles,
								saddlePoints.get(neighbourPeakId), highestSaddle, true);

						// Check for interruption after each merge
						if (Utils.isInterrupted())
							return null;
					}
				}
			}

			if (isLogging)
				IJ.log("Size above saddle filter : Number of peaks = " + countPeaks(peakIdMap));

			// TODO - Add an intensity above saddle filter.
			// All code is in place so this should be a copy of the code above.
			// However what should the intensity above the saddle be relative to? 
			// - It could be an absolute value. This is image specific.
			// - It could be relative to the total peak intensity.
		}

		resultsArray = removeFlaggedResults(resultsArray);

		reassignMaxima(maxima, peakIdMap);

		updateSaddleDetails(resultsArray, peakIdMap);

		return resultsArray;
	}

	private ArrayList<double[]> removeFlaggedResults(ArrayList<double[]> resultsArray)
	{
		// Remove merged peaks from the results
		sortDescResults(resultsArray, FindFoci.SORT_INTENSITY, null);

		//while (resultsArray.size() > 0 && resultsArray.get(resultsArray.size() - 1)[FindFoci.RESULT_INTENSITY] == Double.NEGATIVE_INFINITY)
		//	resultsArray.remove(resultsArray.size() - 1);

		int toIndex = 0;
		while (toIndex < resultsArray.size() &&
				resultsArray.get(toIndex)[FindFoci.RESULT_INTENSITY] != Double.NEGATIVE_INFINITY)
			toIndex++;
		if (toIndex < resultsArray.size())
			resultsArray = new ArrayList<double[]>(resultsArray.subList(0, toIndex));
		return resultsArray;
	}

	private ArrayList<double[]> trim(ArrayList<double[]> resultsArray, int maxPeaks)
	{
		//while (resultsArray.size() > maxPeaks)
		//{
		//	resultsArray.remove(resultsArray.size() - 1);
		//}

		if (maxPeaks < resultsArray.size())
			resultsArray = new ArrayList<double[]>(resultsArray.subList(0, maxPeaks));
		return resultsArray;
	}

	private void removePeak(int[] maxima, int[] peakIdMap, double[] result, int peakId)
	{
		// No neighbour so just remove
		mergePeak(maxima, peakIdMap, peakId, result, 0, null, null, null, null, false);
	}

	private int countPeaks(int[] peakIdMap)
	{
		int count = 0;
		for (int i = 1; i < peakIdMap.length; i++)
		{
			if (peakIdMap[i] == i)
			{
				count++;
			}
		}
		return count;
	}

	private void updateSaddleDetails(ArrayList<double[]> resultsArray, int[] peakIdMap)
	{
		for (double[] result : resultsArray)
		{
			int neighbourPeakId = peakIdMap[(int) result[FindFoci.RESULT_SADDLE_NEIGHBOUR_ID]];

			// Ensure the peak is not marked as a saddle with itself
			if (neighbourPeakId == (int) result[FindFoci.RESULT_PEAK_ID])
				neighbourPeakId = 0;

			if (neighbourPeakId == 0)
				clearSaddle(result);
			else
				result[FindFoci.RESULT_SADDLE_NEIGHBOUR_ID] = neighbourPeakId;
		}
	}

	private void clearSaddle(double[] result)
	{
		result[FindFoci.RESULT_COUNT_ABOVE_SADDLE] = result[FindFoci.RESULT_COUNT];
		result[FindFoci.RESULT_INTENSITY_ABOVE_SADDLE] = result[FindFoci.RESULT_INTENSITY];
		result[FindFoci.RESULT_SADDLE_NEIGHBOUR_ID] = 0;
		result[FindFoci.RESULT_HIGHEST_SADDLE_VALUE] = 0;
	}

	/**
	 * Find the highest saddle that has not been assigned to the specified peak
	 * 
	 * @param peakIdMap
	 * @param peakId
	 * @param saddles
	 * @return
	 */
	private double[] findHighestNeighbourSaddle(int[] peakIdMap, LinkedList<double[]> saddles, int peakId)
	{
		double[] maxSaddle = null;
		double max = 0;
		for (double[] saddle : saddles)
		{
			// Find foci that have not been reassigned to this peak (or nothing)
			final int neighbourPeakId = peakIdMap[(int) saddle[SADDLE_PEAK_ID]];
			if (neighbourPeakId != peakId && neighbourPeakId != 0)
			{
				if (max < saddle[SADDLE_VALUE])
				{
					max = saddle[SADDLE_VALUE];
					maxSaddle = saddle;
				}
			}
		}
		return maxSaddle;
	}

	/**
	 * Find the highest saddle that has been assigned to the specified peak
	 * 
	 * @param peakIdMap
	 * @param peakId
	 * @param saddles
	 * @return
	 */
	private double[] findHighestSaddle(int[] peakIdMap, LinkedList<double[]> saddles, int peakId)
	{
		double[] maxSaddle = null;
		double max = 0;
		for (double[] saddle : saddles)
		{
			// Use the map to ensure the original saddle id corresponds to the current peaks
			final int neighbourPeakId = peakIdMap[(int) saddle[SADDLE_PEAK_ID]];
			if (neighbourPeakId == peakId)
			{
				if (max < saddle[SADDLE_VALUE])
				{
					max = saddle[SADDLE_VALUE];
					maxSaddle = saddle;
				}
			}
		}
		return maxSaddle;
	}

	/**
	 * Find the result for the peak in the results array
	 * 
	 * @param resultsArray
	 * @param id
	 * @return
	 */
	private double[] findResult(ArrayList<double[]> resultsArray, double id)
	{
		for (double[] result : resultsArray)
		{
			if (result[FindFoci.RESULT_PEAK_ID] == id)
				return result;
		}
		return null;
	}

	/**
	 * Assigns the peak to the neighbour. Flags the peak as merged by setting the intensity to zero.
	 * If the highest saddle is lowered then recomputes the size/intensity above the saddle.
	 * 
	 * @param maxima
	 * @param peakIdMap
	 * @param peakId
	 * @param result
	 * @param neighbourPeakId
	 * @param neighbourResult
	 * @param linkedList
	 * @param peakSaddles
	 * @param highestSaddle
	 */
	private void mergePeak(int[] maxima, int[] peakIdMap, int peakId, double[] result, int neighbourPeakId,
			double[] neighbourResult, LinkedList<double[]> peakSaddles, LinkedList<double[]> neighbourSaddles,
			double[] highestSaddle, boolean updatePeakAboveSaddle)
	{
		if (neighbourResult != null)
		{
			//			IJ.log("Merging " + peakId + " (" + result[FindFoci.RESULT_COUNT] + ") -> " + neighbourPeakId + " (" +
			//					neighbourResult[FindFoci.RESULT_COUNT] + ")");

			// Assign this peak's statistics to the neighbour
			neighbourResult[FindFoci.RESULT_INTENSITY] += result[FindFoci.RESULT_INTENSITY];
			neighbourResult[FindFoci.RESULT_COUNT] += result[FindFoci.RESULT_COUNT];

			neighbourResult[FindFoci.RESULT_AVERAGE_INTENSITY] = neighbourResult[FindFoci.RESULT_INTENSITY] /
					neighbourResult[FindFoci.RESULT_COUNT];

			// Check if the neighbour is higher and reassign the maximum point
			if (neighbourResult[FindFoci.RESULT_MAX_VALUE] < result[FindFoci.RESULT_MAX_VALUE])
			{
				neighbourResult[FindFoci.RESULT_MAX_VALUE] = result[FindFoci.RESULT_MAX_VALUE];
				neighbourResult[FindFoci.RESULT_X] = result[FindFoci.RESULT_X];
				neighbourResult[FindFoci.RESULT_Y] = result[FindFoci.RESULT_Y];
				neighbourResult[FindFoci.RESULT_Z] = result[FindFoci.RESULT_Z];
			}

			// Merge the saddles
			for (double[] peakSaddle : peakSaddles)
			{
				final int saddlePeakId = peakIdMap[(int) peakSaddle[SADDLE_PEAK_ID]];
				final double[] neighbourSaddle = findHighestSaddle(peakIdMap, neighbourSaddles, saddlePeakId);
				if (neighbourSaddle == null)
				{
					// The neighbour peak does not touch this peak, add to the list
					neighbourSaddles.add(peakSaddle);
				}
				else
				{
					// Check if the saddle is higher
					if (neighbourSaddle[SADDLE_VALUE] < peakSaddle[SADDLE_VALUE])
					{
						neighbourSaddle[SADDLE_VALUE] = peakSaddle[SADDLE_VALUE];
					}
				}
			}

			// Free memory
			peakSaddles.clear();
		}
		// else
		// {
		// IJ.log("Merging " + peakId + " (" + result[FindFoci.RESULT_COUNT] + ") -> " + neighbourPeakId);
		// }

		// Map anything previously mapped to this peak to the new neighbour
		for (int i = peakIdMap.length; i-- > 0;)
		{
			if (peakIdMap[i] == peakId)
				peakIdMap[i] = neighbourPeakId;
		}

		// Flag this result as merged using the intensity flag. This will be used later to eliminate peaks
		result[FindFoci.RESULT_INTENSITY] = Double.NEGATIVE_INFINITY;

		// Update the count and intensity above the highest neighbour saddle
		if (neighbourResult != null)
		{
			final double[] newHighestSaddle = findHighestNeighbourSaddle(peakIdMap, neighbourSaddles, neighbourPeakId);
			if (newHighestSaddle != null)
			{
				reanalysePeak(maxima, peakIdMap, neighbourPeakId, newHighestSaddle, neighbourResult,
						updatePeakAboveSaddle);
			}
			else
			{
				clearSaddle(neighbourResult);
			}
		}
	}

	/**
	 * Reassign the maxima using the peak Id map and recounts all pixels above the saddle height.
	 * 
	 * @param maxima
	 * @param peakIdMap
	 * @param updatePeakAboveSaddle
	 */
	private void reanalysePeak(int[] maxima, int[] peakIdMap, int peakId, double[] saddle, double[] result,
			boolean updatePeakAboveSaddle)
	{
		if (updatePeakAboveSaddle)
		{
			int peakSize = 0;
			int peakIntensity = 0;
			final double saddleHeight = saddle[1];
			for (int i = maxima.length; i-- > 0;)
			{
				if (maxima[i] > 0)
				{
					maxima[i] = peakIdMap[maxima[i]];
					if (maxima[i] == peakId)
					{
						if (getf(i) > saddleHeight)
						{
							peakIntensity += getf(i);
							peakSize++;
						}
					}
				}
			}

			result[FindFoci.RESULT_COUNT_ABOVE_SADDLE] = peakSize;
			result[FindFoci.RESULT_INTENSITY_ABOVE_SADDLE] = peakIntensity;
		}

		result[FindFoci.RESULT_SADDLE_NEIGHBOUR_ID] = peakIdMap[(int) saddle[SADDLE_PEAK_ID]];
		result[FindFoci.RESULT_HIGHEST_SADDLE_VALUE] = saddle[SADDLE_VALUE];
	}

	/**
	 * Reassign the maxima using the peak Id map
	 * 
	 * @param maxima
	 * @param peakIdMap
	 */
	protected void reassignMaxima(int[] maxima, int[] peakIdMap)
	{
		for (int i = maxima.length; i-- > 0;)
		{
			if (maxima[i] != 0)
			{
				maxima[i] = peakIdMap[maxima[i]];
			}
		}
	}

	/**
	 * Removes any maxima that have pixels that touch the edge.
	 *
	 * @param resultsArray
	 *            the results array
	 * @param maxima
	 *            the maxima
	 * @param stats
	 *            the stats
	 * @param isLogging
	 *            the is logging
	 * @return the results array
	 */
	private ArrayList<double[]> removeEdgeMaxima(ArrayList<double[]> resultsArray, int[] maxima, double[] stats,
			boolean isLogging)
	{
		// Build a look-up table for all the peak IDs
		double maxId = 0;
		for (double[] result : resultsArray)
			if (maxId < result[FindFoci.RESULT_PEAK_ID])
				maxId = result[FindFoci.RESULT_PEAK_ID];

		final int[] peakIdMap = new int[(int) maxId + 1];
		for (int i = 0; i < peakIdMap.length; i++)
			peakIdMap[i] = i;

		// Support the ROI bounds used to create the analysis region
		final int lowerx, upperx, lowery, uppery;
		if (bounds != null)
		{
			lowerx = bounds.x;
			lowery = bounds.y;
			upperx = bounds.x + bounds.width;
			uppery = bounds.y + bounds.height;
		}
		else
		{
			lowerx = 0;
			upperx = maxx;
			lowery = 0;
			uppery = maxy;
		}

		// Set the look-up to zero if the peak contains edge pixels
		for (int z = maxz; z-- > 0;)
		{
			// Look at top and bottom column
			for (int y = uppery, i = getIndex(lowerx, lowery, z), ii = getIndex(upperx - 1, lowery,
					z); y-- > lowery; i += maxx, ii += maxx)
			{
				peakIdMap[maxima[i]] = 0;
				peakIdMap[maxima[ii]] = 0;
			}
			// Look at top and bottom row
			for (int x = upperx, i = getIndex(lowerx, lowery, z), ii = getIndex(lowerx, uppery - 1,
					z); x-- > lowerx; i++, ii++)
			{
				peakIdMap[maxima[i]] = 0;
				peakIdMap[maxima[ii]] = 0;
			}
		}

		// Mark maxima to be removed
		for (double[] result : resultsArray)
		{
			final int peakId = (int) result[FindFoci.RESULT_PEAK_ID];
			if (peakIdMap[peakId] == 0)
				result[FindFoci.RESULT_INTENSITY] = Double.NEGATIVE_INFINITY;
		}

		resultsArray = removeFlaggedResults(resultsArray);

		reassignMaxima(maxima, peakIdMap);

		updateSaddleDetails(resultsArray, peakIdMap);

		return resultsArray;
	}

	/**
	 * Sort the results using the specified index in descending order
	 * 
	 * @param resultsArray
	 * @param sortIndex
	 */
	void sortDescResults(ArrayList<double[]> resultsArray, int sortIndex, double[] stats)
	{
		Collections.sort(resultsArray, new ResultDescComparator(getResultIndex(sortIndex, resultsArray, stats)));
	}

	/**
	 * Sort the results using the specified index in ascending order
	 * 
	 * @param resultsArray
	 * @param sortIndex
	 */
	void sortAscResults(ArrayList<double[]> resultsArray, int sortIndex, double[] stats)
	{
		Collections.sort(resultsArray, new ResultAscComparator(getResultIndex(sortIndex, resultsArray, stats)));
	}

	private int getResultIndex(int sortIndex, ArrayList<double[]> resultsArray, double[] stats)
	{
		switch (sortIndex)
		{
			case FindFoci.SORT_INTENSITY:
				return FindFoci.RESULT_INTENSITY;
			case FindFoci.SORT_INTENSITY_MINUS_BACKGROUND:
				return FindFoci.RESULT_INTENSITY_MINUS_BACKGROUND;
			case FindFoci.SORT_COUNT:
				return FindFoci.RESULT_COUNT;
			case FindFoci.SORT_MAX_VALUE:
				return FindFoci.RESULT_MAX_VALUE;
			case FindFoci.SORT_AVERAGE_INTENSITY:
				return FindFoci.RESULT_AVERAGE_INTENSITY;
			case FindFoci.SORT_AVERAGE_INTENSITY_MINUS_BACKGROUND:
				return FindFoci.RESULT_AVERAGE_INTENSITY_MINUS_BACKGROUND;
			case FindFoci.SORT_X:
				return FindFoci.RESULT_X;
			case FindFoci.SORT_Y:
				return FindFoci.RESULT_Y;
			case FindFoci.SORT_Z:
				return FindFoci.RESULT_Z;
			case FindFoci.SORT_SADDLE_HEIGHT:
				return FindFoci.RESULT_HIGHEST_SADDLE_VALUE;
			case FindFoci.SORT_COUNT_ABOVE_SADDLE:
				return FindFoci.RESULT_COUNT_ABOVE_SADDLE;
			case FindFoci.SORT_INTENSITY_ABOVE_SADDLE:
				return FindFoci.RESULT_INTENSITY_ABOVE_SADDLE;
			case FindFoci.SORT_ABSOLUTE_HEIGHT:
				customSortAbsoluteHeight(resultsArray, round(stats[FindFoci.STATS_BACKGROUND]));
				return FindFoci.RESULT_CUSTOM_SORT_VALUE;
			case FindFoci.SORT_RELATIVE_HEIGHT_ABOVE_BACKGROUND:
				customSortRelativeHeightAboveBackground(resultsArray, round(stats[FindFoci.STATS_BACKGROUND]));
				return FindFoci.RESULT_CUSTOM_SORT_VALUE;
			case FindFoci.SORT_PEAK_ID:
				return FindFoci.RESULT_PEAK_ID;
			case FindFoci.SORT_XYZ:
				customSortXYZ(resultsArray);
				return FindFoci.RESULT_CUSTOM_SORT_VALUE;
		}
		return FindFoci.RESULT_INTENSITY;
	}

	private void customSortAbsoluteHeight(ArrayList<double[]> resultsArray, double background)
	{
		for (double[] result : resultsArray)
		{
			result[FindFoci.RESULT_CUSTOM_SORT_VALUE] = getAbsoluteHeight(result, background);
		}
	}

	private void customSortRelativeHeightAboveBackground(ArrayList<double[]> resultsArray, double background)
	{
		for (double[] result : resultsArray)
		{
			double absoluteHeight = getAbsoluteHeight(result, background);
			result[FindFoci.RESULT_CUSTOM_SORT_VALUE] = getRelativeHeight(result, background, absoluteHeight);
		}
	}

	private void customSortXYZ(ArrayList<double[]> resultsArray)
	{
		final int a = maxy * maxz;
		final int b = maxz;
		for (double[] result : resultsArray)
		{
			final int x = (int) result[FindFoci.RESULT_X];
			final int y = (int) result[FindFoci.RESULT_Y];
			final int z = (int) result[FindFoci.RESULT_Z];
			result[FindFoci.RESULT_CUSTOM_SORT_VALUE] = x * a + y * b + z;
		}
	}

	/**
	 * Initialises the global width, height and depth variables. Creates the direction offset tables.
	 */
	private void initialise(ImagePlus imp)
	{
		maxx = imp.getWidth();
		maxy = imp.getHeight();
		maxz = imp.getNSlices();

		// Used to look-up x,y,z from a single index
		maxx_maxy = maxx * maxy;
		maxx_maxy_maxz = maxx * maxy * maxz;

		xlimit = maxx - 1;
		ylimit = maxy - 1;
		zlimit = maxz - 1;
		dStart = (maxz == 1) ? 8 : 26;

		// Create the offset table (for single array 3D neighbour comparisons)
		offset = new int[DIR_X_OFFSET.length];
		flatEdge = new boolean[DIR_X_OFFSET.length];
		for (int d = offset.length; d-- > 0;)
		{
			offset[d] = getIndex(DIR_X_OFFSET[d], DIR_Y_OFFSET[d], DIR_Z_OFFSET[d]);
			flatEdge[d] = (Math.abs(DIR_X_OFFSET[d]) + Math.abs(DIR_Y_OFFSET[d]) + Math.abs(DIR_Z_OFFSET[d]) == 1);
		}
	}

	/**
	 * Return the single index associated with the x,y,z coordinates
	 * 
	 * @param x
	 * @param y
	 * @param z
	 * @return The index
	 */
	protected int getIndex(int x, int y, int z)
	{
		return (maxx_maxy) * z + maxx * y + x;
	}

	/**
	 * Convert the single index into x,y,z coords, Input array must be length >= 3.
	 * 
	 * @param index
	 * @param xyz
	 * @return The xyz array
	 */
	protected int[] getXYZ(int index, int[] xyz)
	{
		xyz[2] = index / (maxx_maxy);
		int mod = index % (maxx_maxy);
		xyz[1] = mod / maxx;
		xyz[0] = mod % maxx;
		return xyz;
	}

	/**
	 * Convert the single index into x,y,z coords, Input array must be length >= 3.
	 * 
	 * @param index
	 * @param xyz
	 * @return The xyz array
	 */
	protected int[] getXY(int index, int[] xyz)
	{
		int mod = index % (maxx_maxy);
		xyz[1] = mod / maxx;
		xyz[0] = mod % maxx;
		return xyz;
	}

	/**
	 * Debugging method
	 * 
	 * @param index
	 *            the single x,y,z index
	 * @return The x coordinate
	 */
	protected int getX(int index)
	{
		int mod = index % (maxx_maxy);
		return mod % maxx;
	}

	/**
	 * Debugging method
	 * 
	 * @param index
	 *            the single x,y,z index
	 * @return The x coordinate
	 */
	protected int getY(int index)
	{
		int mod = index % (maxx_maxy);
		return mod / maxx;
	}

	/**
	 * Debugging method
	 * 
	 * @param index
	 *            the single x,y,z index
	 * @return The x coordinate
	 */
	protected int getZ(int index)
	{
		return index / (maxx_maxy);
	}

	/**
	 * returns whether the neighbour in a given direction is within the image. NOTE: it is assumed that the pixel x,y
	 * itself is within the image! Uses class variables xlimit, ylimit: (dimensions of the image)-1
	 * 
	 * @param x
	 *            x-coordinate of the pixel that has a neighbour in the given direction
	 * @param y
	 *            y-coordinate of the pixel that has a neighbour in the given direction
	 * @param direction
	 *            the direction from the pixel towards the neighbour
	 * @return true if the neighbour is within the image (provided that x, y is within)
	 */
	protected boolean isWithinXY(int x, int y, int direction)
	{
		switch (direction)
		{
			case 0:
				return (y > 0);
			case 1:
				return (y > 0 && x < xlimit);
			case 2:
				return (x < xlimit);
			case 3:
				return (y < ylimit && x < xlimit);
			case 4:
				return (y < ylimit);
			case 5:
				return (y < ylimit && x > 0);
			case 6:
				return (x > 0);
			case 7:
				return (y > 0 && x > 0);
			case 8:
				return true;
		}
		return false;
	}

	/**
	 * returns whether the neighbour in a given direction is within the image. NOTE: it is assumed that the pixel x,y,z
	 * itself is within the image! Uses class variables xlimit, ylimit, zlimit: (dimensions of the image)-1
	 * 
	 * @param x
	 *            x-coordinate of the pixel that has a neighbour in the given direction
	 * @param y
	 *            y-coordinate of the pixel that has a neighbour in the given direction
	 * @param z
	 *            z-coordinate of the pixel that has a neighbour in the given direction
	 * @param direction
	 *            the direction from the pixel towards the neighbour
	 * @return true if the neighbour is within the image (provided that x, y, z is within)
	 */
	protected boolean isWithinXYZ(int x, int y, int z, int direction)
	{
		switch (direction)
		{
			case 0:
				return (y > 0);
			case 1:
				return (y > 0 && x < xlimit);
			case 2:
				return (x < xlimit);
			case 3:
				return (y < ylimit && x < xlimit);
			case 4:
				return (y < ylimit);
			case 5:
				return (y < ylimit && x > 0);
			case 6:
				return (x > 0);
			case 7:
				return (y > 0 && x > 0);
			case 8:
				return (z > 0 && y > 0);
			case 9:
				return (z > 0 && y > 0 && x < xlimit);
			case 10:
				return (z > 0 && x < xlimit);
			case 11:
				return (z > 0 && y < ylimit && x < xlimit);
			case 12:
				return (z > 0 && y < ylimit);
			case 13:
				return (z > 0 && y < ylimit && x > 0);
			case 14:
				return (z > 0 && x > 0);
			case 15:
				return (z > 0 && y > 0 && x > 0);
			case 16:
				return (z > 0);
			case 17:
				return (z < zlimit && y > 0);
			case 18:
				return (z < zlimit && y > 0 && x < xlimit);
			case 19:
				return (z < zlimit && x < xlimit);
			case 20:
				return (z < zlimit && y < ylimit && x < xlimit);
			case 21:
				return (z < zlimit && y < ylimit);
			case 22:
				return (z < zlimit && y < ylimit && x > 0);
			case 23:
				return (z < zlimit && x > 0);
			case 24:
				return (z < zlimit && y > 0 && x > 0);
			case 25:
				return (z < zlimit);
		}
		return false;
	}

	/**
	 * returns whether the neighbour in a given direction is within the image. NOTE: it is assumed that the pixel z
	 * itself is within the image! Uses class variables zlimit: (dimensions of the image)-1
	 * 
	 * @param z
	 *            z-coordinate of the pixel that has a neighbour in the given direction
	 * @param direction
	 *            the direction from the pixel towards the neighbour
	 * @return true if the neighbour is within the image (provided that z is within)
	 */
	protected boolean isWithinZ(int z, int direction)
	{
		// z = 0
		if (direction < 8)
			return true;
		// z = -1
		if (direction < 17)
			return (z > 0);
		// z = 1
		return z < zlimit;
	}

	/**
	 * Check if the logging flag is enabled
	 * 
	 * @param outputType
	 *            The output options flag
	 * @return True if logging
	 */
	private boolean isLogging(int outputType)
	{
		return (outputType & FindFoci.OUTPUT_LOG_MESSAGES) != 0;
	}

	/**
	 * Stores the details of a pixel position.
	 */
	protected class Coordinate implements Comparable<Coordinate>
	{
		public int index;
		public int id;
		public float value;

		public Coordinate(int index, int id, float value)
		{
			this.index = index;
			this.id = id;
			this.value = value;
		}

		public Coordinate(int x, int y, int z, int id, float value)
		{
			this.index = getIndex(x, y, z);
			this.id = id;
			this.value = value;
		}

		/*
		 * (non-Javadoc)
		 * 
		 * @see java.lang.Comparable#compareTo(java.lang.Object)
		 */
		public int compareTo(Coordinate o)
		{
			// Require the sort to rank the highest peak as first.
			// Since sort works in ascending order return a negative for a higher value.
			if (value > o.value)
				return -1;
			if (value < o.value)
				return 1;
			return 0;
		}
	}

	/**
	 * Provides the ability to sort the results arrays in descending order
	 */
	private class ResultDescComparator implements Comparator<double[]>
	{
		private int sortIndex = 0;

		public ResultDescComparator(int sortIndex)
		{
			this.sortIndex = sortIndex;
		}

		/*
		 * (non-Javadoc)
		 * 
		 * @see java.util.Comparator#compare(java.lang.Object, java.lang.Object)
		 */
		public int compare(double[] o1, double[] o2)
		{
			// Require the highest is first
			if (o1[sortIndex] > o2[sortIndex])
				return -1;
			if (o1[sortIndex] < o2[sortIndex])
				return 1;
			return 0;
		}
	}

	/**
	 * Provides the ability to sort the results arrays in ascending order
	 */
	private class ResultAscComparator implements Comparator<double[]>
	{
		private int sortIndex = 0;

		public ResultAscComparator(int sortIndex)
		{
			this.sortIndex = sortIndex;
		}

		/*
		 * (non-Javadoc)
		 * 
		 * @see java.util.Comparator#compare(java.lang.Object, java.lang.Object)
		 */
		public int compare(double[] o1, double[] o2)
		{
			// Require the lowest is first
			if (o1[sortIndex] > o2[sortIndex])
				return 1;
			if (o1[sortIndex] < o2[sortIndex])
				return -1;
			return 0;
		}
	}

	/**
	 * Used to store the number of objects and their original mask value (state)
	 */
	class ObjectAnalysisResult
	{
		int numberOfObjects;
		int[] objectState;
		int[] fociCount;
	}

	/**
	 * Identify all non-zero pixels in the mask image as potential objects. Mark connected pixels with the same value as
	 * a single object. For each maxima identify the object and original mask value for the maxima location.
	 * 
	 * @param mask
	 *            The mask containing objects
	 * @param maximaImp
	 * @param resultsArray
	 * @param createObjectMask
	 * @return The mask image if created
	 */
	ImagePlus doObjectAnalysis(ImagePlus mask, ImagePlus maximaImp, ArrayList<double[]> resultsArray,
			boolean createObjectMask, ObjectAnalysisResult objectAnalysisResult)
	{
		if (resultsArray == null || resultsArray.isEmpty())
		{
			// Allow the analysis to continue if we are creating the object mask or storing the analysis results
			if (!createObjectMask && objectAnalysisResult == null)
				return null;
		}

		final int[] maskImage = extractMask(mask);
		if (maskImage == null)
			return null;

		// Track all the objects. Allow more than the 16-bit capacity for counting objects.
		final int[] objects = new int[maskImage.length];
		int id = 0;
		int[] objectState = new int[10];
		// Label for 2D/3D processing
		final boolean is2D = (maskImage.length == maxx_maxy);

		int[] pList = new int[100];

		for (int i = 0; i < maskImage.length; i++)
		{
			if (maskImage[i] != 0 && objects[i] == 0)
			{
				id++;

				// Store the original mask value of new object
				if (objectState.length <= id)
					objectState = Arrays.copyOf(objectState, (int) (objectState.length * 1.5));
				objectState[id] = maskImage[i];

				if (is2D)
					pList = expandObjectXY(maskImage, objects, i, id, pList);
				else
					pList = expandObjectXYZ(maskImage, objects, i, id, pList);
			}
		}

		// For each maximum, mark the object and original mask value (state).
		// Count the number of foci in each object.
		int[] fociCount = new int[id + 1];
		if (resultsArray != null)
		{
			for (double[] result : resultsArray)
			{
				final int x = (int) result[FindFoci.RESULT_X];
				final int y = (int) result[FindFoci.RESULT_Y];
				final int z = (is2D) ? 0 : (int) result[FindFoci.RESULT_Z];
				final int index = getIndex(x, y, z);
				final int objectId = objects[index];
				result[FindFoci.RESULT_OBJECT] = objectId;
				result[FindFoci.RESULT_STATE] = objectState[objectId];
				fociCount[objectId]++;
			}
		}

		// Store the number of objects and their orignal mask value
		if (objectAnalysisResult != null)
		{
			objectAnalysisResult.numberOfObjects = id;
			objectAnalysisResult.objectState = objectState;
			objectAnalysisResult.fociCount = fociCount;
		}

		// Show the object mask
		ImagePlus maskImp = null;
		if (createObjectMask)
		{
			// Check we do not exceed capcity
			if (id > MAXIMA_CAPCITY)
			{
				IJ.log("The number of objects exceeds the 16-bit capacity used for diplay: " + MAXIMA_CAPCITY);
				return null;
			}

			final int n = (is2D) ? 1 : maxz;
			ImageStack stack = new ImageStack(maxx, maxy, n);
			for (int z = 0, index = 0; z < n; z++)
			{
				final short[] pixels = new short[maxx_maxy];
				for (int i = 0; i < pixels.length; i++, index++)
					pixels[i] = (short) objects[index];
				stack.setPixels(pixels, z + 1);
			}
			// Create a new ImagePlus so that the stack and calibration can be set
			ImagePlus imp = new ImagePlus("", stack);
			imp.setCalibration(maximaImp.getCalibration());
			maskImp = FindFoci.showImage(imp, mask.getTitle() + " Objects", false);
		}

		return maskImp;
	}

	/**
	 * Extract the mask image.
	 *
	 * @param mask
	 *            the mask
	 * @return The mask image array
	 */
	private int[] extractMask(ImagePlus mask)
	{
		if (mask == null)
			return null;

		// Check sizes in X & Y
		if (mask.getWidth() != maxx || mask.getHeight() != maxy ||
				(mask.getNSlices() != maxz && mask.getNSlices() != 1))
		{
			return null;
		}

		final int maxx_maxy = maxx * maxy;
		final int[] image;
		if (mask.getNSlices() == 1)
		{
			// Extract a single plane
			ImageProcessor ipMask = mask.getProcessor();

			image = new int[maxx_maxy];
			for (int i = maxx_maxy; i-- > 0;)
			{
				image[i] = ipMask.get(i);
			}
		}
		else
		{
			final int maxx_maxy_maxz = maxx * maxy * maxz;
			// If the same stack size then process through the image
			final ImageStack stack = mask.getStack();
			final int c = mask.getChannel();
			final int f = mask.getFrame();
			image = new int[maxx_maxy_maxz];
			for (int slice = 1; slice <= mask.getNSlices(); slice++)
			{
				final int stackIndex = mask.getStackIndex(c, slice, f);
				ImageProcessor ipMask = stack.getProcessor(stackIndex);

				int index = maxx_maxy * slice;
				for (int i = maxx_maxy; i-- > 0;)
				{
					index--;
					image[index] = ipMask.get(i);
				}
			}
		}

		return image;
	}

	/**
	 * Searches from the specified point to find all coordinates of the same value and assigns them to given maximum ID.
	 */
	private int[] expandObjectXYZ(final int[] image, final int[] maxima, final int index0, final int id, int[] pList)
	{
		maxima[index0] = id; // mark first point
		int listI = 0; // index of current search element in the list
		int listLen = 1; // number of elements in the list

		// we create a list of connected points and start the list at the current point
		pList[listI] = index0;

		final int[] xyz = new int[3];
		final int v0 = image[index0];

		do
		{
			final int index1 = pList[listI];
			getXYZ(index1, xyz);
			final int x1 = xyz[0];
			final int y1 = xyz[1];
			final int z1 = xyz[2];

			// It is more likely that the z stack will be out-of-bounds.
			// Adopt the xy limit lookup and process z lookup separately

			final boolean isInnerXY = (y1 != 0 && y1 != ylimit) && (x1 != 0 && x1 != xlimit);
			final boolean isInnerXYZ = (zlimit == 0) ? isInnerXY : isInnerXY && (z1 != 0 && z1 != zlimit);

			for (int d = 26; d-- > 0;)
			{
				if (isInnerXYZ || (isInnerXY && isWithinZ(z1, d)) || isWithinXYZ(x1, y1, z1, d))
				{
					final int index2 = index1 + offset[d];
					if (maxima[index2] != 0)
					{
						// This has been done already, ignore this point
						continue;
					}

					final int v2 = image[index2];

					if (v2 == v0)
					{
						// Add this to the search
						pList[listLen++] = index2;
						maxima[index2] = id;
						if (pList.length == listLen)
							pList = Arrays.copyOf(pList, (int) (listLen * 1.5));
					}
				}
			}

			listI++;

		} while (listI < listLen);

		return pList;
	}

	/**
	 * Searches from the specified point to find all coordinates of the same value and assigns them to given maximum ID.
	 */
	private int[] expandObjectXY(final int[] image, final int[] maxima, final int index0, final int id, int[] pList)
	{
		maxima[index0] = id; // mark first point
		int listI = 0; // index of current search element in the list
		int listLen = 1; // number of elements in the list

		// we create a list of connected points and start the list at the current point
		pList[listI] = index0;

		final int[] xyz = new int[2];
		final int v0 = image[index0];

		do
		{
			final int index1 = pList[listI];
			getXY(index1, xyz);
			final int x1 = xyz[0];
			final int y1 = xyz[1];

			final boolean isInnerXY = (y1 != 0 && y1 != ylimit) && (x1 != 0 && x1 != xlimit);

			for (int d = 8; d-- > 0;)
			{
				if (isInnerXY || isWithinXY(x1, y1, d))
				{
					final int index2 = index1 + offset[d];
					if (maxima[index2] != 0)
					{
						// This has been done already, ignore this point
						continue;
					}

					final int v2 = image[index2];

					if (v2 == v0)
					{
						// Add this to the search
						pList[listLen++] = index2;
						maxima[index2] = id;
						if (pList.length == listLen)
							pList = Arrays.copyOf(pList, (int) (listLen * 1.5));
					}
				}
			}

			listI++;

		} while (listI < listLen);

		return pList;
	}
}