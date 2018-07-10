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
package gdsc.foci;

import gdsc.core.threshold.Histogram;
import ij.ImagePlus;

/**
 * Contains the results of the FindFoci algorithm after the initialisation stage.
 */
public class FindFociInitResults
{
	public Object image;
	public byte[] types;
	public int[] maxima;
	public Histogram histogram;
	public FindFociStatistics stats;
	public Object originalImage;
	public ImagePlus originalImp;

	/**
	 * Instantiates a new find foci init results.
	 *
	 * @param image
	 *            the image
	 * @param types
	 *            the types
	 * @param maxima
	 *            the maxima
	 * @param histgram
	 *            the histgram
	 * @param stats
	 *            the stats
	 * @param originalImage
	 *            the original image
	 * @param originalImp
	 *            the original imp
	 */
	public FindFociInitResults(Object image, byte[] types, int[] maxima, Histogram histgram, FindFociStatistics stats,
			Object originalImage, ImagePlus originalImp)
	{
		this.image = image;
		this.types = types;
		this.maxima = maxima;
		this.histogram = histgram;
		this.stats = stats;
		this.originalImage = originalImage;
		this.originalImp = originalImp;
	}
}
