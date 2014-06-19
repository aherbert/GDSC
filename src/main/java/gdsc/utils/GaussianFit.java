package gdsc.utils;

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

import ij.IJ;
import ij.ImagePlus;
import ij.gui.Roi;
import ij.plugin.filter.PlugInFilter;
import ij.process.FloatProcessor;
import ij.process.ImageProcessor;

import java.awt.Rectangle;
import java.lang.reflect.Method;

/**
 * Fits a circular 2D Gaussian.
 */
@SuppressWarnings({ "unchecked", "rawtypes" })
public class GaussianFit implements PlugInFilter
{
	private static boolean fittingEnabled;
	private static String errorMessage;
	private static Throwable exception;

	// Try and perform a Gaussian fit
	static
	{
		fittingEnabled = false;
		errorMessage = "";
		exception = null;

		int size = 99;
		float mu = size / 2;
		float s0 = 3;
		float N = 10;

		// Create a 2D Gaussian curve
		float[] data = new float[size * size];
		for (int y = 0; y < size; y++)
			for (int x = 0; x < size; x++)
				data[y * size + x] = (float) (N * (Math.exp(-0.5 * ((mu - x) * (mu - x) + 0.5 * (mu - y) * (mu - y)) /
						(s0 * s0))));

		try
		{
			// Get a class in this package to find the package class loader
			GaussianPlugin pluginClass = new GaussianPlugin();
			Class c = Class.forName("gdsc.smlm.ij.plugins.GaussianFit", true, pluginClass.getClass().getClassLoader());

			// ... it exists on the classpath
			
			// TODO: get this to work calling the method with reflection ...

			// Try a fit. 
			double[] fit = null;
			//gdsc.smlm.ij.plugins.GaussianFit gf = new gdsc.smlm.ij.plugins.GaussianFit();
			//fit = gf.fit(data, size, size);

			// Use reflection to allow building without the SMLM plugins on the classpath
			Method m = c.getDeclaredMethod("fit", new Class[] { float[].class, int.class, int.class });
			fit = (double[]) m.invoke(c.newInstance(), data, size, size);
			if (fit != null)
			{
				fittingEnabled = true;
			}
		}
		catch (ExceptionInInitializerError e)
		{
			exception = e;
			errorMessage = "Failed to initialize class: " + e.getMessage();
		}
		catch (LinkageError e)
		{
			exception = e;
			errorMessage = "Failed to link class: " + e.getMessage();
		}
		catch (ClassNotFoundException ex)
		{
			exception = ex;
			errorMessage = "Failed to find class: " + ex.getMessage();

			//StringWriter sw = new StringWriter();
			//PrintWriter pw = new PrintWriter(sw);
			//ex.printStackTrace(pw);
			//IJ.log(sw.toString());
		}
		catch (Exception ex)
		{
			exception = ex;
			errorMessage = ex.getMessage();
		}
		
		if (!fittingEnabled)
		{
			System.out.println(errorMessage);
		}
	}

	private void init(float[] data, int width, int height)
	{
		if (data == null || data.length != width * height || width < 1 || height < 1)
			throw new IllegalArgumentException("Data must be same length as width * height");
	}

	/**
	 * @param data
	 * @param width
	 * @param height
	 * @return The fitted Gaussian parameters (Background, Amplitude, x0, x1, s)
	 */
	public double[] fit(float[] data, int width, int height)
	{
		init(data, width, height);
		//gdsc.smlm.ij.plugins.GaussianFit gf = new gdsc.smlm.ij.plugins.GaussianFit();
		//return gf.fit(data, width, height);

		try
		{
			// Use reflection to allow building without the SMLM plugins on the classpath
			Class c = Class.forName("gdsc.smlm.ij.plugins.GaussianFit", true, this.getClass().getClassLoader());
			Method m = c.getDeclaredMethod("fit", new Class[] { float[].class, int.class, int.class });
			return (double[]) m.invoke(c.newInstance(), data, width, height);
		}
		catch (Exception e)
		{
			return null;
		}
	}

	/**
	 * @return true if fitting is possible
	 */
	public boolean isFittingEnabled()
	{
		return fittingEnabled;
	}

	/**
	 * @return the errorMessage if fitting is not possible
	 */
	public String getErrorMessage()
	{
		return errorMessage;
	}

	/**
	 * @return the exception if fitting is not possible
	 */
	public Throwable getException()
	{
		return exception;
	}

	/*
	 * (non-Javadoc)
	 * 
	 * @see ij.plugin.filter.PlugInFilter#setup(java.lang.String, ij.ImagePlus)
	 */
	public int setup(String arg, ImagePlus imp)
	{
		Roi roi = imp.getRoi();
		if (roi == null || !roi.isArea())
		{
			IJ.error("Require a region ROI");
			return DONE;
		}
		return DOES_ALL | NO_CHANGES;
	}

	/*
	 * (non-Javadoc)
	 * 
	 * @see ij.plugin.filter.PlugInFilter#run(ij.process.ImageProcessor)
	 */
	public void run(ImageProcessor ip)
	{
		FloatProcessor fp = ip.toFloat(0, null);
		Rectangle bounds = fp.getRoi();
		fp = (FloatProcessor) fp.crop();

		double[] params = fit((float[]) fp.getPixels(), fp.getWidth(), fp.getHeight());
		if (params != null)
			IJ.log(String.format("f(x,y) = %g + %g exp -( ( (x-%g)^2 / (2 * %g^2) + (y-%g)^2 ) / (2 * %g^2) )",
					params[0], params[1], bounds.x + params[2], params[4], bounds.y + params[3], params[5]));
	}
}
