package gdsc.threshold;

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

import gdsc.utils.ImageJHelper;
import ij.IJ;
import ij.ImagePlus;
import ij.ImageStack;
import ij.WindowManager;
import ij.gui.GenericDialog;
import ij.gui.Roi;
import ij.plugin.PlugIn;
import ij.process.ByteProcessor;
import ij.process.ImageProcessor;

import java.awt.Rectangle;
import java.util.ArrayList;

/**
 * Create a mask from an image
 */
public class MaskCreater implements PlugIn
{
	public static String[] options = new String[] { "Use as mask", "Min Display Value", "Use ROI", "Threshold" };
	public static int OPTION_MASK = 0;
	public static int OPTION_MIN_VALUE = 1;
	public static int OPTION_USE_ROI = 2;
	public static int OPTION_THRESHOLD = 3;

	private static String selectedImage = "";
	private static int selectedOption = OPTION_MASK;
	private static String selectedThresholdMethod = "Otsu";
	private static int selectedChannel = 0;
	private static int selectedSlice = 0;
	private static int selectedFrame = 0;

	private ImagePlus imp;
	private int option;
	private String thresholdMethod;
	private int channel = 0;
	private int slice = 0;
	private int frame = 0;

	/*
	 * (non-Javadoc)
	 * 
	 * @see ij.plugin.PlugIn#run(java.lang.String)
	 */
	public void run(String arg)
	{
		if (!showDialog())
		{
			return;
		}
		ImagePlus imp = createMask();
		if (imp != null)
		{
			imp.show();
		}
	}

	private boolean showDialog()
	{
		ArrayList<String> imageList = new ArrayList<String>();

		for (int id : ImageJHelper.getIDList())
		{
			ImagePlus imp = WindowManager.getImage(id);
			if (imp != null)
			{
				imageList.add(imp.getTitle());
			}
		}

		if (imageList.isEmpty())
		{
			IJ.noImage();
			return false;
		}

		GenericDialog gd = new GenericDialog("Mask Creator");
		gd.addMessage("Create a new mask image");
		gd.addChoice("Image", imageList.toArray(new String[0]), selectedImage);
		gd.addChoice("Option", options, options[selectedOption]);
		gd.addChoice("Threshold_Method", Auto_Threshold.methods, selectedThresholdMethod);
		gd.addNumericField("Channel", selectedChannel, 0);
		gd.addNumericField("Slice", selectedSlice, 0);
		gd.addNumericField("Frame", selectedFrame, 0);
		gd.addHelp(gdsc.help.URL.UTILITY);
		gd.showDialog();

		if (gd.wasCanceled())
			return false;

		selectedImage = gd.getNextChoice();
		selectedOption = gd.getNextChoiceIndex();
		selectedThresholdMethod = gd.getNextChoice();
		selectedChannel = (int) gd.getNextNumber();
		selectedSlice = (int) gd.getNextNumber();
		selectedFrame = (int) gd.getNextNumber();

		setImp(WindowManager.getImage(selectedImage));
		setOption(selectedOption);
		setThresholdMethod(selectedThresholdMethod);
		setChannel(selectedChannel);
		setSlice(selectedSlice);
		setFrame(selectedFrame);

		return true;
	}

	public MaskCreater()
	{
		init(null, OPTION_MASK);
	}

	public MaskCreater(ImagePlus imp)
	{
		init(imp, OPTION_MASK);
	}

	public MaskCreater(ImagePlus imp, int option)
	{
		init(imp, option);
	}

	private void init(ImagePlus imp, int option)
	{
		this.imp = imp;
		this.option = option;
	}

	/**
	 * Create a mask using the configured source image.
	 * 
	 * @return The mask image
	 */
	public ImagePlus createMask()
	{
		ImagePlus maskImp = null;

		if (imp == null)
			return maskImp;

		ByteProcessor bp;

		ImageStack inputStack = imp.getImageStack();
		int currentSlice = imp.getCurrentSlice();
		ImageStack result = null;
		int[] dimensions = imp.getDimensions();
		int[] channels = createArray(dimensions[2], channel);
		int[] slices = createArray(dimensions[3], slice);
		int[] frames = createArray(dimensions[4], frame);

		int[] thresholds = null;
		if (option == OPTION_THRESHOLD)
		{
			thresholds = getThresholds(imp, channels, slices, frames);
		}

		if (option == OPTION_MIN_VALUE || option == OPTION_MASK || option == OPTION_THRESHOLD)
		{
			// Use the ROI image to create a mask either using:
			// - non-zero pixels (i.e. a mask)
			// - all pixels above the minimum display value
			result = new ImageStack(imp.getWidth(), imp.getHeight());

			for (int frame : frames)
				for (int slice : slices)
					for (int channel : channels)
					{
						int stackIndex = imp.getStackIndex(channel, slice, frame);
						ImageProcessor roiIp = inputStack.getProcessor(stackIndex);

						double min = 1;
						if (option == OPTION_MIN_VALUE)
						{
							// Must update the hyperstack to get the image processor display range 
							if (imp.isDisplayedHyperStack())
								imp.setPosition(stackIndex);
							else
								imp.setSlice(stackIndex);
							min = imp.getDisplayRangeMin();
						}
						else if (option == OPTION_THRESHOLD)
						{
							min = thresholds[stackIndex - 1];
						}

						bp = new ByteProcessor(imp.getWidth(), imp.getHeight());
						for (int i = roiIp.getPixelCount(); i-- > 0;)
						{
							if (roiIp.get(i) >= min)
							{
								bp.set(i, 255);
							}
						}
						result.addSlice(null, bp);
					}

			// Reset the stack
			if (imp.isDisplayedHyperStack())
				imp.setPosition(currentSlice);
			else
				imp.setSlice(currentSlice);
		}

		if (option == OPTION_USE_ROI)
		{
			// Use the ROI from the ROI image
			Roi roi = imp.getRoi();

			Rectangle bounds;
			if (roi != null)
				bounds = roi.getBounds();
			else
				// If no ROI then use the entire image
				bounds = new Rectangle(imp.getWidth(), imp.getHeight());

			// Use a mask for an irregular ROI
			ImageProcessor ipMask = imp.getMask();

			// Create a mask from the ROI rectangle
			int xOffset = bounds.x;
			int yOffset = bounds.y;
			int rwidth = bounds.width;
			int rheight = bounds.height;

			result = new ImageStack(imp.getWidth(), imp.getHeight());

			bp = new ByteProcessor(imp.getWidth(), imp.getHeight());
			for (int y = 0; y < rheight; y++)
			{
				for (int x = 0; x < rwidth; x++)
				{
					if (ipMask == null || ipMask.get(x, y) != 0)
					{
						bp.set(x + xOffset, y + yOffset, 255);
					}
				}
			}

			for (int frame=frames.length; frame-- > 0; )
				for (int slice=slices.length; slice-- > 0; )
					for (int channel=channels.length; channel-- > 0; )
					{
						result.addSlice(null, bp.duplicate());
					}
		}

		if (result != null)
		{
			maskImp = new ImagePlus(imp.getShortTitle() + " Mask", result);
			if (imp.isDisplayedHyperStack())
			{
				int nChannels = channels.length;
				int nSlices = slices.length;
				int nFrames = frames.length;
				if (nChannels * nSlices * nFrames > 1)
				{
					maskImp.setDimensions(nChannels, nSlices, nFrames);
					maskImp.setOpenAsHyperStack(true);
				}
			}
		}

		return maskImp;
	}

	private int[] getThresholds(ImagePlus imp, int[] channels, int[] slices, int[] frames)
	{
		int[] thresholds = new int[imp.getStackSize()];
		ImageStack inputStack = imp.getImageStack();

		for (int frame : frames)
			for (int channel : channels)
			{
				// Threshold the z-stack together
				int stackIndex = imp.getStackIndex(channel, slices[0], frame);
				int[] data = inputStack.getProcessor(stackIndex).getHistogram();
				for (int i = 1; i < slices.length; i++)
				{
					int[] tmp = inputStack.getProcessor(stackIndex).getHistogram();
					for (int j = tmp.length; j-- > 0;)
						data[j] += tmp[j];
				}
				int threshold = Auto_Threshold.getThreshold(thresholdMethod, data);
				for (int slice : slices)
				{
					stackIndex = imp.getStackIndex(channel, slice, frame);
					thresholds[stackIndex - 1] = threshold;
				}
			}
		return thresholds;
	}

	private int[] createArray(int total, int selected)
	{
		if (selected > 0 && selected <= total)
		{
			return new int[] { selected };
		}
		int[] array = new int[total];
		for (int i = 0; i < array.length; i++)
			array[i] = i + 1;
		return array;
	}

	/**
	 * @param imp
	 *            the source image for the mask generation
	 */
	public void setImp(ImagePlus imp)
	{
		this.imp = imp;
	}

	/**
	 * @return the source image for the mask generation
	 */
	public ImagePlus getImp()
	{
		return imp;
	}

	/**
	 * @param option
	 *            the option for defining the mask
	 */
	public void setOption(int option)
	{
		this.option = option;
	}

	/**
	 * @return the option for defining the mask
	 */
	public int getOption()
	{
		return option;
	}

	/**
	 * @param thresholdMethod
	 *            the thresholdMethod to set
	 */
	public void setThresholdMethod(String thresholdMethod)
	{
		this.thresholdMethod = thresholdMethod;
	}

	/**
	 * @return the thresholdMethod
	 */
	public String getThresholdMethod()
	{
		return thresholdMethod;
	}

	/**
	 * @param channel
	 *            the channel to set
	 */
	public void setChannel(int channel)
	{
		this.channel = channel;
	}

	/**
	 * @return the channel
	 */
	public int getChannel()
	{
		return channel;
	}

	/**
	 * @param frame
	 *            the frame to set
	 */
	public void setFrame(int frame)
	{
		this.frame = frame;
	}

	/**
	 * @return the frame
	 */
	public int getFrame()
	{
		return frame;
	}

	/**
	 * @param slice
	 *            the slice to set
	 */
	public void setSlice(int slice)
	{
		this.slice = slice;
	}

	/**
	 * @return the slice
	 */
	public int getSlice()
	{
		return slice;
	}
}
