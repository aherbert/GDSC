package gdsc.foci;

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

import gdsc.clustering.Cluster;
import gdsc.clustering.ClusterPoint;
import gdsc.clustering.ClusteringAlgorithm;
import gdsc.clustering.ClusteringEngine;
import gdsc.clustering.IJTrackProgress;
import gdsc.help.URL;
import gdsc.utils.ImageJHelper;
import ij.IJ;
import ij.ImagePlus;
import ij.ImageStack;
import ij.Prefs;
import ij.WindowManager;
import ij.gui.DialogListener;
import ij.gui.GenericDialog;
import ij.plugin.filter.ExtendedPlugInFilter;
import ij.plugin.filter.PlugInFilterRunner;
import ij.process.ImageProcessor;
import ij.process.LUT;
import ij.text.TextWindow;

import java.awt.AWTEvent;
import java.awt.Label;
import java.awt.Point;
import java.awt.image.ColorModel;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;

import org.apache.commons.math3.stat.descriptive.DescriptiveStatistics;

/**
 * Performs clustering on the latest Find Foci result held in memory. Optionally can draw the clusters on the Find Foci
 * output mask if this is selected when the plugin is run.
 */
public class AssignFociToClusters implements ExtendedPlugInFilter, DialogListener
{
	public static final String FRAME_TITLE = "Assign Foci To Clusters";

	private static int imageFlags = DOES_8G + DOES_16 + SNAPSHOT;
	private static int noImageFlags = NO_IMAGE_REQUIRED + FINAL_PROCESSING;

	private static double radius = 100;
	//@formatter:off
	private static ClusteringAlgorithm[] algorithms = new ClusteringAlgorithm[] {
			ClusteringAlgorithm.PARTICLE_SINGLE_LINKAGE, 
			ClusteringAlgorithm.CENTROID_LINKAGE,
			ClusteringAlgorithm.PARTICLE_CENTROID_LINKAGE, 
			ClusteringAlgorithm.PAIRWISE,
			ClusteringAlgorithm.PAIRWISE_WITHOUT_NEIGHBOURS 
	};
	private static String[] names;
	static
	{
		names = new String[algorithms.length];
		for (int i = 0; i < names.length; i++)
			names[i] = algorithms[i].toString();
	}
	private static int algorithm = 1;
	private static String[] weights = new String[] {
		"None",
		"Pixel count", 
		"Total intensity",
		"Max Value",
		"Average intensity",
		"Total intensity minus background",
		"Average intensity minus background",
		"Count above saddle",
		"Intensity above saddle"
	};	
	private static int[] weight_result_index = new int[] {
		-1,
        FindFoci.RESULT_COUNT,
        FindFoci.RESULT_INTENSITY,
        FindFoci.RESULT_MAX_VALUE,
        FindFoci.RESULT_AVERAGE_INTENSITY,
        FindFoci.RESULT_INTENSITY_MINUS_BACKGROUND,
        FindFoci.RESULT_AVERAGE_INTENSITY_MINUS_BACKGROUND,
        FindFoci.RESULT_COUNT_ABOVE_SADDLE,
        FindFoci.RESULT_INTENSITY_ABOVE_SADDLE
	};
	//@formatter:on
	private static int weight = 0;
	private static boolean showMask = true;
	private boolean myShowMask = false;

	private static TextWindow resultsWindow = null;
	private static TextWindow summaryWindow = null;
	private static int resultId = 1;

	private ImagePlus imp;
	private ArrayList<int[]> results;
	private ArrayList<Cluster> clusters;
	private ColorModel cm;
	private Label label = null;

	public int setup(String arg, ImagePlus imp)
	{
		if (arg.equals("final"))
		{
			doClustering();
			displayResults();
			return DONE;
		}
		results = FindFoci.getResults();
		if (results == null || results.isEmpty())
		{
			IJ.error(FRAME_TITLE, "Require " + FindFoci.FRAME_TITLE + " results in memory");
			return DONE;
		}

		this.imp = validateInputImage(imp);

		return (this.imp == null) ? noImageFlags : imageFlags;
	}

	public void resetPreview()
	{
		if (this.imp != null)
		{
			// Reset the preview 
			ImageProcessor ip = this.imp.getProcessor();
			ip.setColorModel(cm);
			ip.reset();
		}
	}

	/**
	 * Check if the input image has the same number of non-zero values as the FindFoci results. This means it is a
	 * FindFoci mask for the results.
	 * 
	 * @param imp
	 * @return The image if valid
	 */
	private ImagePlus validateInputImage(ImagePlus imp)
	{
		if (imp == null)
			return null;
		if (imp.getBitDepth() != 8 && imp.getBitDepth() != 16)
			return null;

		// Find all the mask objects using a stack histogram.
		ImageStack stack = imp.getImageStack();
		int[] h = stack.getProcessor(1).getHistogram();
		for (int s = 2; s <= stack.getSize(); s++)
		{
			int[] h2 = stack.getProcessor(1).getHistogram();
			for (int i = 0; i < h.length; i++)
				h[i] += h2[i];
		}

		// Correct mask objects should be numbered sequentially from 1.
		// Find first number that is zero.
		int size = 1;
		while (size < h.length)
		{
			if (h[size] == 0)
				break;
			size++;
		}
		size--; // Decrement to find the last non-zero number 

		// Check the FindFoci results have the same number of objects
		if (size != results.size())
			return null;

		// Check each result matches the image.
		// Image values correspond to the reverse order of the results.
		for (int i = 0, id = results.size(); i < results.size(); i++, id--)
		{
			final int[] result = results.get(i);
			final int x = result[FindFoci.RESULT_X];
			final int y = result[FindFoci.RESULT_Y];
			final int z = result[FindFoci.RESULT_Z];
			try
			{
				final int value = stack.getProcessor(z + 1).get(x, y);
				if (value != id)
				{
					System.out.printf("x%d,y%d,z%d %d != %d\n", x, y, z + 1, value, id);
					return null;
				}
			}
			catch (IllegalArgumentException e)
			{
				// The stack is not the correct size
				System.out.println(e.getMessage());
				return null;
			}
		}

		// Store this so it can be reset
		cm = imp.getProcessor().getColorModel();

		return imp;
	}

	public void run(ImageProcessor ip)
	{
		// This will not be called if we selected NO_IMAGE_REQUIRED

		doClustering();

		if (label == null)
		{
			// This occurs when the dialog has been closed and the plugin is run.
			displayResults();
			return;
		}

		// This occurs when we are supporting a preview
		label.setText(ImageJHelper.pleural(clusters.size(), "Cluster"));

		// Create a new mask image colouring the objects from each cluster.
		// Create a map to convert original foci pixels to clusters.
		int[] map = new int[results.size() + 1];
		for (int i = 0; i < clusters.size(); i++)
		{
			for (ClusterPoint p = clusters.get(i).head; p != null; p = p.next)
			{
				map[p.id] = i + 1;
			}
		}

		// Update the preview processor with the clusters
		for (int i = 0; i < ip.getPixelCount(); i++)
		{
			if (ip.get(i) != 0)
				ip.set(i, map[ip.get(i)]);
		}
		ip.setColorModel(getColorModel());
		ip.setMinAndMax(0, clusters.size());
	}

	/**
	 * Build a custom LUT that helps show the classes
	 * 
	 * @return
	 */
	private LUT getColorModel()
	{
		// Create a colour LUT so that all colours from 1-255 are distinct.
		// This was produced using http://phrogz.net/css/distinct-colors.html
		byte[] reds = new byte[256];
		byte[] greens = new byte[256];
		byte[] blues = new byte[256];

		int i = 1;
		rgb(255, 0, 0, reds, greens, blues, i++);
		rgb(153, 135, 115, reds, greens, blues, i++);
		rgb(76, 217, 54, reds, greens, blues, i++);
		rgb(0, 136, 255, reds, greens, blues, i++);
		rgb(129, 105, 140, reds, greens, blues, i++);
		rgb(242, 0, 0, reds, greens, blues, i++);
		rgb(255, 170, 0, reds, greens, blues, i++);
		rgb(0, 51, 0, reds, greens, blues, i++);
		rgb(0, 102, 191, reds, greens, blues, i++);
		rgb(204, 0, 255, reds, greens, blues, i++);
		rgb(166, 0, 0, reds, greens, blues, i++);
		rgb(191, 128, 0, reds, greens, blues, i++);
		rgb(127, 255, 145, reds, greens, blues, i++);
		rgb(0, 75, 140, reds, greens, blues, i++);
		rgb(184, 54, 217, reds, greens, blues, i++);
		rgb(51, 0, 0, reds, greens, blues, i++);
		rgb(115, 77, 0, reds, greens, blues, i++);
		rgb(83, 166, 94, reds, greens, blues, i++);
		rgb(0, 48, 89, reds, greens, blues, i++);
		rgb(230, 128, 255, reds, greens, blues, i++);
		rgb(255, 128, 128, reds, greens, blues, i++);
		rgb(76, 51, 0, reds, greens, blues, i++);
		rgb(38, 77, 43, reds, greens, blues, i++);
		rgb(128, 196, 255, reds, greens, blues, i++);
		rgb(46, 26, 51, reds, greens, blues, i++);
		rgb(153, 77, 77, reds, greens, blues, i++);
		rgb(51, 34, 0, reds, greens, blues, i++);
		rgb(143, 191, 150, reds, greens, blues, i++);
		rgb(191, 225, 255, reds, greens, blues, i++);
		rgb(71, 0, 77, reds, greens, blues, i++);
		rgb(76, 38, 38, reds, greens, blues, i++);
		rgb(229, 172, 57, reds, greens, blues, i++);
		rgb(0, 140, 37, reds, greens, blues, i++);
		rgb(77, 90, 102, reds, greens, blues, i++);
		rgb(133, 35, 140, reds, greens, blues, i++);
		rgb(51, 26, 26, reds, greens, blues, i++);
		rgb(153, 115, 38, reds, greens, blues, i++);
		rgb(0, 89, 24, reds, greens, blues, i++);
		rgb(0, 102, 255, reds, greens, blues, i++);
		rgb(99, 51, 102, reds, greens, blues, i++);
		rgb(255, 191, 191, reds, greens, blues, i++);
		rgb(255, 213, 128, reds, greens, blues, i++);
		rgb(0, 255, 102, reds, greens, blues, i++);
		rgb(0, 61, 153, reds, greens, blues, i++);
		rgb(188, 143, 191, reds, greens, blues, i++);
		rgb(115, 86, 86, reds, greens, blues, i++);
		rgb(178, 149, 89, reds, greens, blues, i++);
		rgb(115, 230, 161, reds, greens, blues, i++);
		rgb(0, 20, 51, reds, greens, blues, i++);
		rgb(255, 0, 238, reds, greens, blues, i++);
		rgb(64, 48, 48, reds, greens, blues, i++);
		rgb(102, 85, 51, reds, greens, blues, i++);
		rgb(191, 255, 217, reds, greens, blues, i++);
		rgb(128, 179, 255, reds, greens, blues, i++);
		rgb(255, 191, 251, reds, greens, blues, i++);
		rgb(76, 10, 0, reds, greens, blues, i++);
		rgb(204, 187, 153, reds, greens, blues, i++);
		rgb(115, 153, 130, reds, greens, blues, i++);
		rgb(83, 116, 166, reds, greens, blues, i++);
		rgb(77, 57, 75, reds, greens, blues, i++);
		rgb(255, 89, 64, reds, greens, blues, i++);
		rgb(255, 204, 0, reds, greens, blues, i++);
		rgb(77, 102, 87, reds, greens, blues, i++);
		rgb(57, 80, 115, reds, greens, blues, i++);
		rgb(179, 0, 143, reds, greens, blues, i++);
		rgb(191, 67, 48, reds, greens, blues, i++);
		rgb(166, 133, 0, reds, greens, blues, i++);
		rgb(0, 204, 109, reds, greens, blues, i++);
		rgb(153, 173, 204, reds, greens, blues, i++);
		rgb(115, 0, 92, reds, greens, blues, i++);
		rgb(115, 40, 29, reds, greens, blues, i++);
		rgb(102, 82, 0, reds, greens, blues, i++);
		rgb(0, 128, 68, reds, greens, blues, i++);
		rgb(105, 119, 140, reds, greens, blues, i++);
		rgb(64, 0, 51, reds, greens, blues, i++);
		rgb(217, 123, 108, reds, greens, blues, i++);
		rgb(217, 184, 54, reds, greens, blues, i++);
		rgb(0, 51, 27, reds, greens, blues, i++);
		rgb(0, 68, 255, reds, greens, blues, i++);
		rgb(230, 57, 195, reds, greens, blues, i++);
		rgb(115, 65, 57, reds, greens, blues, i++);
		rgb(76, 65, 19, reds, greens, blues, i++);
		rgb(26, 102, 66, reds, greens, blues, i++);
		rgb(0, 48, 179, reds, greens, blues, i++);
		rgb(255, 128, 229, reds, greens, blues, i++);
		rgb(204, 160, 153, reds, greens, blues, i++);
		rgb(204, 184, 102, reds, greens, blues, i++);
		rgb(83, 166, 127, reds, greens, blues, i++);
		rgb(0, 34, 128, reds, greens, blues, i++);
		rgb(191, 96, 172, reds, greens, blues, i++);
		rgb(217, 58, 0, reds, greens, blues, i++);
		rgb(127, 115, 64, reds, greens, blues, i++);
		rgb(153, 204, 180, reds, greens, blues, i++);
		rgb(0, 24, 89, reds, greens, blues, i++);
		rgb(77, 38, 69, reds, greens, blues, i++);
		rgb(127, 34, 0, reds, greens, blues, i++);
		rgb(255, 242, 191, reds, greens, blues, i++);
		rgb(0, 255, 170, reds, greens, blues, i++);
		rgb(51, 92, 204, reds, greens, blues, i++);
		rgb(255, 0, 170, reds, greens, blues, i++);
		rgb(89, 24, 0, reds, greens, blues, i++);
		rgb(153, 145, 115, reds, greens, blues, i++);
		rgb(0, 179, 119, reds, greens, blues, i++);
		rgb(29, 52, 115, reds, greens, blues, i++);
		rgb(153, 0, 102, reds, greens, blues, i++);
		rgb(51, 14, 0, reds, greens, blues, i++);
		rgb(102, 97, 77, reds, greens, blues, i++);
		rgb(115, 230, 191, reds, greens, blues, i++);
		rgb(19, 34, 77, reds, greens, blues, i++);
		rgb(89, 0, 60, reds, greens, blues, i++);
		rgb(255, 115, 64, reds, greens, blues, i++);
		rgb(64, 61, 48, reds, greens, blues, i++);
		rgb(0, 255, 204, reds, greens, blues, i++);
		rgb(128, 162, 255, reds, greens, blues, i++);
		rgb(153, 77, 128, reds, greens, blues, i++);
		rgb(166, 75, 41, reds, greens, blues, i++);
		rgb(255, 238, 0, reds, greens, blues, i++);
		rgb(0, 128, 102, reds, greens, blues, i++);
		rgb(32, 40, 64, reds, greens, blues, i++);
		rgb(115, 86, 105, reds, greens, blues, i++);
		rgb(255, 162, 128, reds, greens, blues, i++);
		rgb(153, 143, 0, reds, greens, blues, i++);
		rgb(0, 77, 61, reds, greens, blues, i++);
		rgb(191, 208, 255, reds, greens, blues, i++);
		rgb(255, 0, 136, reds, greens, blues, i++);
		rgb(191, 121, 96, reds, greens, blues, i++);
		rgb(115, 107, 0, reds, greens, blues, i++);
		rgb(13, 51, 43, reds, greens, blues, i++);
		rgb(57, 62, 77, reds, greens, blues, i++);
		rgb(178, 0, 95, reds, greens, blues, i++);
		rgb(140, 89, 70, reds, greens, blues, i++);
		rgb(51, 48, 13, reds, greens, blues, i++);
		rgb(57, 115, 103, reds, greens, blues, i++);
		rgb(0, 27, 204, reds, greens, blues, i++);
		rgb(127, 0, 68, reds, greens, blues, i++);
		rgb(76, 48, 38, reds, greens, blues, i++);
		rgb(255, 247, 128, reds, greens, blues, i++);
		rgb(191, 255, 242, reds, greens, blues, i++);
		rgb(0, 8, 64, reds, greens, blues, i++);
		rgb(51, 0, 27, reds, greens, blues, i++);
		rgb(255, 208, 191, reds, greens, blues, i++);
		rgb(191, 188, 143, reds, greens, blues, i++);
		rgb(57, 77, 73, reds, greens, blues, i++);
		rgb(102, 116, 204, reds, greens, blues, i++);
		rgb(217, 54, 141, reds, greens, blues, i++);
		rgb(153, 125, 115, reds, greens, blues, i++);
		rgb(238, 255, 0, reds, greens, blues, i++);
		rgb(0, 255, 238, reds, greens, blues, i++);
		rgb(143, 150, 191, reds, greens, blues, i++);
		rgb(255, 128, 196, reds, greens, blues, i++);
		rgb(255, 102, 0, reds, greens, blues, i++);
		rgb(167, 179, 0, reds, greens, blues, i++);
		rgb(0, 179, 167, reds, greens, blues, i++);
		rgb(0, 0, 255, reds, greens, blues, i++);
		rgb(115, 57, 88, reds, greens, blues, i++);
		rgb(191, 77, 0, reds, greens, blues, i++);
		rgb(59, 64, 0, reds, greens, blues, i++);
		rgb(115, 230, 222, reds, greens, blues, i++);
		rgb(0, 0, 153, reds, greens, blues, i++);
		rgb(51, 26, 39, reds, greens, blues, i++);
		rgb(140, 56, 0, reds, greens, blues, i++);
		rgb(160, 166, 83, reds, greens, blues, i++);
		rgb(77, 153, 148, reds, greens, blues, i++);
		rgb(0, 0, 140, reds, greens, blues, i++);
		rgb(255, 191, 225, reds, greens, blues, i++);
		rgb(64, 26, 0, reds, greens, blues, i++);
		rgb(74, 77, 38, reds, greens, blues, i++);
		rgb(153, 204, 201, reds, greens, blues, i++);
		rgb(0, 0, 128, reds, greens, blues, i++);
		rgb(166, 124, 146, reds, greens, blues, i++);
		rgb(255, 140, 64, reds, greens, blues, i++);
		rgb(102, 128, 0, reds, greens, blues, i++);
		rgb(38, 51, 50, reds, greens, blues, i++);
		rgb(0, 0, 102, reds, greens, blues, i++);
		rgb(255, 0, 102, reds, greens, blues, i++);
		rgb(191, 105, 48, reds, greens, blues, i++);
		rgb(71, 89, 0, reds, greens, blues, i++);
		rgb(0, 238, 255, reds, greens, blues, i++);
		rgb(128, 128, 255, reds, greens, blues, i++);
		rgb(153, 0, 61, reds, greens, blues, i++);
		rgb(127, 70, 32, reds, greens, blues, i++);
		rgb(195, 230, 57, reds, greens, blues, i++);
		rgb(0, 179, 191, reds, greens, blues, i++);
		rgb(77, 77, 153, reds, greens, blues, i++);
		rgb(102, 0, 41, reds, greens, blues, i++);
		rgb(89, 49, 22, reds, greens, blues, i++);
		rgb(229, 255, 128, reds, greens, blues, i++);
		rgb(0, 131, 140, reds, greens, blues, i++);
		rgb(191, 191, 255, reds, greens, blues, i++);
		rgb(76, 19, 42, reds, greens, blues, i++);
		rgb(255, 179, 128, reds, greens, blues, i++);
		rgb(103, 115, 57, reds, greens, blues, i++);
		rgb(0, 60, 64, reds, greens, blues, i++);
		rgb(105, 105, 140, reds, greens, blues, i++);
		rgb(204, 102, 143, reds, greens, blues, i++);
		rgb(191, 134, 96, reds, greens, blues, i++);
		rgb(242, 255, 191, reds, greens, blues, i++);
		rgb(51, 99, 102, reds, greens, blues, i++);
		rgb(29, 26, 51, reds, greens, blues, i++);
		rgb(255, 0, 68, reds, greens, blues, i++);
		rgb(140, 98, 70, reds, greens, blues, i++);
		rgb(170, 255, 0, reds, greens, blues, i++);
		rgb(115, 150, 153, reds, greens, blues, i++);
		rgb(121, 96, 191, reds, greens, blues, i++);
		rgb(166, 0, 44, reds, greens, blues, i++);
		rgb(51, 36, 26, reds, greens, blues, i++);
		rgb(110, 166, 0, reds, greens, blues, i++);
		rgb(0, 204, 255, reds, greens, blues, i++);
		rgb(48, 38, 77, reds, greens, blues, i++);
		rgb(76, 0, 20, reds, greens, blues, i++);
		rgb(255, 217, 191, reds, greens, blues, i++);
		rgb(170, 204, 102, reds, greens, blues, i++);
		rgb(0, 102, 128, reds, greens, blues, i++);
		rgb(126, 57, 230, reds, greens, blues, i++);
		rgb(204, 51, 92, reds, greens, blues, i++);
		rgb(191, 163, 143, reds, greens, blues, i++);
		rgb(140, 153, 115, reds, greens, blues, i++);
		rgb(48, 163, 191, reds, greens, blues, i++);
		rgb(84, 38, 153, reds, greens, blues, i++);
		rgb(255, 128, 162, reds, greens, blues, i++);
		rgb(115, 98, 86, reds, greens, blues, i++);
		rgb(34, 64, 0, reds, greens, blues, i++);
		rgb(22, 76, 89, reds, greens, blues, i++);
		rgb(49, 22, 89, reds, greens, blues, i++);
		rgb(153, 77, 97, reds, greens, blues, i++);
		rgb(64, 54, 48, reds, greens, blues, i++);
		rgb(141, 217, 54, reds, greens, blues, i++);
		rgb(128, 230, 255, reds, greens, blues, i++);
		rgb(179, 128, 255, reds, greens, blues, i++);
		rgb(217, 163, 177, reds, greens, blues, i++);
		rgb(255, 136, 0, reds, greens, blues, i++);
		rgb(117, 153, 77, reds, greens, blues, i++);
		rgb(191, 242, 255, reds, greens, blues, i++);
		rgb(80, 57, 115, reds, greens, blues, i++);
		rgb(89, 67, 73, reds, greens, blues, i++);
		rgb(191, 102, 0, reds, greens, blues, i++);
		rgb(39, 51, 26, reds, greens, blues, i++);
		rgb(0, 170, 255, reds, greens, blues, i++);
		rgb(163, 143, 191, reds, greens, blues, i++);
		rgb(217, 0, 29, reds, greens, blues, i++);
		rgb(140, 75, 0, reds, greens, blues, i++);
		rgb(180, 204, 153, reds, greens, blues, i++);
		rgb(0, 128, 191, reds, greens, blues, i++);
		rgb(87, 77, 102, reds, greens, blues, i++);
		rgb(140, 0, 19, reds, greens, blues, i++);
		rgb(102, 54, 0, reds, greens, blues, i++);
		rgb(68, 77, 57, reds, greens, blues, i++);
		rgb(0, 94, 140, reds, greens, blues, i++);
		rgb(43, 38, 51, reds, greens, blues, i++);
		rgb(102, 0, 14, reds, greens, blues, i++);
		rgb(64, 34, 0, reds, greens, blues, i++);
		rgb(102, 255, 0, reds, greens, blues, i++);
		rgb(0, 51, 77, reds, greens, blues, i++);
		rgb(136, 0, 255, reds, greens, blues, i++);
		rgb(217, 54, 76, reds, greens, blues, i++);

		return new LUT(reds, greens, blues);
	}

	private void rgb(int r, int g, int b, byte[] reds, byte[] greens, byte[] blues, int i)
	{
		reds[i] = (byte) (r & 255);
		greens[i] = (byte) (g & 255);
		blues[i] = (byte) (b & 255);
	}

	public void setNPasses(int nPasses)
	{
		// Nothing to do
	}

	public int showDialog(ImagePlus imp, String command, PlugInFilterRunner pfr)
	{
		GenericDialog gd = new GenericDialog(FRAME_TITLE);
		gd.addHelp(URL.FIND_FOCI);
		gd.addMessage(ImageJHelper.pleural(results.size(), "result"));

		gd.addSlider("Radius", 5, 500, radius);
		gd.addChoice("Algorithm", names, names[algorithm]);
		gd.addChoice("Weight", weights, weights[weight]);
		if (this.imp != null)
		{
			gd.addCheckbox("Show_mask", showMask);
		}

		// Allow preview
		gd.addPreviewCheckbox(pfr);
		gd.addDialogListener(this);
		gd.addMessage("");
		label = (Label) gd.getMessage();

		gd.showDialog();

		// Disable preview
		resetPreview();
		label = null;

		if (gd.wasCanceled() || !dialogItemChanged(gd, null))
		{
			return DONE;
		}

		return (this.imp == null) ? noImageFlags : imageFlags;
	}

	public boolean dialogItemChanged(GenericDialog gd, AWTEvent e)
	{
		radius = Math.abs(gd.getNextNumber());
		algorithm = gd.getNextChoiceIndex();
		weight = gd.getNextChoiceIndex();
		if (this.imp != null)
			myShowMask = showMask = gd.getNextBoolean();

		// We can support a preview without an image
		if (label != null && imp == null)
		{
			noImagePreview();
		}

		return true;
	}

	private synchronized void noImagePreview()
	{
		doClustering();
		label.setText(ImageJHelper.pleural(clusters.size(), "Cluster"));
	}

	private double lastRadius;
	private int lastAlgorithm, lastWeight;

	private void doClustering()
	{
		if (clusters == null || lastRadius != radius || lastAlgorithm != algorithm || lastWeight != weight)
		{
			lastRadius = radius;
			lastAlgorithm = algorithm;
			lastWeight = weight;

			long start = System.currentTimeMillis();
			IJ.showStatus("Clustering ...");
			ClusteringEngine e = new ClusteringEngine(Prefs.getThreads(), algorithms[algorithm], new IJTrackProgress());
			ArrayList<ClusterPoint> points = getPoints();
			clusters = e.findClusters(points, radius);
			Collections.sort(clusters, new Comparator<Cluster>()
			{
				public int compare(Cluster o1, Cluster o2)
				{
					if (o1.sumw > o2.sumw)
						return -1;
					if (o1.sumw < o2.sumw)
						return 1;
					return 0;
				}
			});
			double seconds = (System.currentTimeMillis() - start) / 1000.0;
			IJ.showStatus(ImageJHelper.pleural(clusters.size(), "cluster") + " in " + ImageJHelper.rounded(seconds) +
					" seconds");
		}
	}

	private void displayResults()
	{
		if (clusters == null)
			return;

		IJ.showStatus("Displaying results ...");

		createResultsTables();

		// Show the results
		final String title = (imp != null) ? imp.getTitle() : "Result " + (resultId++);
		StringBuilder sb = new StringBuilder();
		DescriptiveStatistics stats = new DescriptiveStatistics();
		DescriptiveStatistics stats2 = new DescriptiveStatistics();
		for (int i = 0; i < clusters.size(); i++)
		{
			final Cluster cluster = clusters.get(i);
			sb.append(title).append('\t');
			sb.append(i + 1).append('\t');
			sb.append(ImageJHelper.rounded(cluster.x)).append('\t');
			sb.append(ImageJHelper.rounded(cluster.y)).append('\t');
			sb.append(ImageJHelper.rounded(cluster.n)).append('\t');
			sb.append(ImageJHelper.rounded(cluster.sumw)).append('\t');
			stats.addValue(cluster.n);
			stats2.addValue(cluster.sumw);
			sb.append('\n');

			// Auto-width adjustment is only performed when number of rows is less than 10
			// so do this before it won't work
			if (i == 9 && resultsWindow.getTextPanel().getLineCount() < 10)
			{
				resultsWindow.append(sb.toString());
				sb.setLength(0);
			}
		}
		resultsWindow.append(sb.toString());

		sb.setLength(0);
		sb.append(title).append('\t');
		sb.append(ImageJHelper.rounded(radius)).append('\t');
		sb.append(results.size()).append('\t');
		sb.append(clusters.size()).append('\t');
		sb.append((int) stats.getMin()).append('\t');
		sb.append((int) stats.getMax()).append('\t');
		sb.append(ImageJHelper.rounded(stats.getMean())).append('\t');
		sb.append(ImageJHelper.rounded(stats2.getMin())).append('\t');
		sb.append(ImageJHelper.rounded(stats2.getMax())).append('\t');
		sb.append(ImageJHelper.rounded(stats2.getMean())).append('\t');
		summaryWindow.append(sb.toString());

		if (myShowMask)
		{
			// Create a new mask image colouring the objects from each cluster.
			// Create a map to convert original foci pixels to clusters.
			int[] map = new int[results.size() + 1];
			for (int i = 0; i < clusters.size(); i++)
			{
				for (ClusterPoint p = clusters.get(i).head; p != null; p = p.next)
				{
					map[p.id] = i + 1;
				}
			}

			ImageStack stack = imp.getImageStack();
			ImageStack newStack = new ImageStack(stack.getWidth(), stack.getHeight(), stack.getSize());
			for (int s = 1; s <= stack.getSize(); s++)
			{
				ImageProcessor ip = stack.getProcessor(s);
				ImageProcessor ip2 = ip.createProcessor(ip.getWidth(), ip.getHeight());
				for (int i = 0; i < ip.getPixelCount(); i++)
				{
					if (ip.get(i) != 0)
						ip2.set(i, map[ip.get(i)]);
				}
				newStack.setProcessor(ip2, s);
			}

			// Set a color table if this is a new image
			ImagePlus clusterImp = WindowManager.getImage(FRAME_TITLE);
			if (clusterImp == null)
				newStack.setColorModel(getColorModel());

			ImageJHelper.display(FRAME_TITLE, newStack);
		}

		IJ.showStatus("");
	}

	private ArrayList<ClusterPoint> getPoints()
	{
		if (FindFoci.getResults() == null)
			return null;
		ArrayList<ClusterPoint> points = new ArrayList<ClusterPoint>(FindFoci.getResults().size());
		// Image values correspond to the reverse order of the results.
		final int weight_index = weight_result_index[weight];
		for (int i = 0, id = results.size(); i < results.size(); i++, id--)
		{
			int[] result = results.get(i);
			final double w = (weight_index != -1) ? result[weight_index] : 1;
			points.add(ClusterPoint.newClusterPoint(id, result[FindFoci.RESULT_X], result[FindFoci.RESULT_Y], w));
		}
		return points;
	}

	private void createResultsTables()
	{
		resultsWindow = createWindow(resultsWindow, "Results", "Title\tCluster\tcx\tcy\tSize\tW");
		summaryWindow = createWindow(summaryWindow, "Summary",
				"Title\tRadius\tFoci\tClusters\tMin\tMax\tAv\tMin W\tMax W\tAv W");
		Point p1 = resultsWindow.getLocation();
		Point p2 = summaryWindow.getLocation();
		if (p1.x == p2.x && p1.y == p2.y)
		{
			p2.y += resultsWindow.getHeight();
			summaryWindow.setLocation(p2);
		}
	}

	private TextWindow createWindow(TextWindow window, String title, String header)
	{
		if (window == null || !window.isVisible())
			window = new TextWindow(FRAME_TITLE + " " + title, header, "", 800, 400);
		return window;
	}
}
