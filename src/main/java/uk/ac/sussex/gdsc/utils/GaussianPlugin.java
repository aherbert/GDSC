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
package uk.ac.sussex.gdsc.utils;

import ij.ImagePlus;
import ij.gui.GenericDialog;
import ij.plugin.PlugIn;
import ij.process.FloatProcessor;
import uk.ac.sussex.gdsc.UsageTracker;

/**
 * Creates a Gaussian image.
 */
public class GaussianPlugin implements PlugIn
{
	private static final String TITLE = "Gaussian";
	private static int width = 256;
	private static int height = 256;
	private static float amplitude = 255;
	private static float x = 100;
	private static float y = 130;
	private static float x_sd = 20;
	private static float y_sd = 10;
	private static float angle = 0f;
	private static float noise = 10f;

	/*
	 * (non-Javadoc)
	 *
	 * @see ij.plugin.PlugIn#run(java.lang.String)
	 */
	@Override
	public void run(String arg)
	{
		UsageTracker.recordPlugin(this.getClass(), arg);
		final GenericDialog gd = new GenericDialog(TITLE);
		gd.addNumericField("Width", width, 0);
		gd.addNumericField("Height", height, 0);
		gd.addNumericField("Amplitude", amplitude, 0);
		gd.addNumericField("X", x, 1);
		gd.addNumericField("Y", y, 1);
		gd.addNumericField("X_sd", x_sd, 1);
		gd.addNumericField("Y_sd", y_sd, 1);
		gd.addSlider("Angle", 0, 180, angle);
		gd.addNumericField("Noise", noise, 1);

		gd.showDialog();
		if (gd.wasCanceled())
			return;

		width = (int) gd.getNextNumber();
		height = (int) gd.getNextNumber();
		amplitude = (float) gd.getNextNumber();
		x = (float) gd.getNextNumber();
		y = (float) gd.getNextNumber();
		x_sd = (float) gd.getNextNumber();
		y_sd = (float) gd.getNextNumber();
		angle = (float) gd.getNextNumber();
		noise = (float) gd.getNextNumber();

		final float[] img = createGaussian(width, height, new float[] { amplitude }, new float[] { x },
				new float[] { y }, new float[] { x_sd }, new float[] { y_sd },
				new float[] { (float) (angle * Math.PI / 180.0) });
		final FloatProcessor fp = new FloatProcessor(width, height, img, null);
		if (noise > 0)
			fp.noise(noise);
		new ImagePlus("Gaussian", fp).show();
	}

	private static float[] createGaussian(int width, int height, float[] amplitude, float[] xpos, float[] ypos,
			float[] sx, float[] sy, float[] angle)
	{
		final float[] img = new float[width * height];

		for (int i = 0; i < 1; i++)
		{
			final float sigma_x = sx[i];
			final float sigma_y = sy[i];
			final float theta = angle[i];

			final float a = (float) (Math.cos(theta) * Math.cos(theta) / (2 * sigma_x * sigma_x) +
					Math.sin(theta) * Math.sin(theta) / (2 * sigma_y * sigma_y));
			final float b = (float) (Math.sin(2 * theta) / (4 * sigma_x * sigma_x) -
					Math.sin(2 * theta) / (4 * sigma_y * sigma_y));
			final float c = (float) (Math.sin(theta) * Math.sin(theta) / (2 * sigma_x * sigma_x) +
					Math.cos(theta) * Math.cos(theta) / (2 * sigma_y * sigma_y));

			int index = 0;
			for (int yi = 0; yi < height; yi++)
				for (int xi = 0; xi < width; xi++)
					img[index++] += gaussian(xi, yi, amplitude[i], xpos[i], ypos[i], a, b, c);
		}

		return img;
	}

	/**
	 * Generic form of the 2D Gaussian
	 */
	private static float gaussian(float x, float y, float A, float x0, float y0, float a, float b, float c)
	{
		return (float) (A *
				Math.exp(-(a * (x - x0) * (x - x0) + 2 * b * (x - x0) * (y - y0) + c * (y - y0) * (y - y0))));
	}
}