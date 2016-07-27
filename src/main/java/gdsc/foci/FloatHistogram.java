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

class FloatHistogram extends Histogram
{
	final float[] value;

	private FloatHistogram(int[] h, float[] value, int minBin, int maxBin)
	{
		super(h, minBin, maxBin);
		this.value = value;
	}

	public FloatHistogram(float[] value, int[] h)
	{
		super(h);
		this.value = value;
	}

	@Override
	public Histogram compact(int size)
	{
		if (minBin == maxBin)
			return this;
		float min = getValue(minBin);
		float max = getValue(maxBin);

		if ((int) min == min && (int) max == max && min >= 0 && max < size)
		{
			// Check if we can convert to integer histogram
			if (integerData())
				return integerHistogram(size);
		}

		// Compress non-integer data
		int size_1 = size - 1;
		float binSize = (max - min) / size_1;
		int[] newH = new int[size];
		for (int i = 0; i < h.length; i++)
		{
			int bin = (int) ((getValue(i) - min) / binSize + 0.5);
			if (bin < 0)
				bin = 0;
			if (bin >= size)
				bin = size_1;
			newH[bin] += h[i];
		}
		// Create the new values
		float[] newValue = new float[size];
		for (int i = 0; i < size; i++)
			newValue[i] = min + i * binSize;
		return new FloatHistogram(newValue, newH);
	}

	private boolean integerData()
	{
		for (float f : value)
			if ((int) f != f)
				return false;
		return true;
	}

	private Histogram integerHistogram(int size)
	{
		int[] h = new int[size];
		for (int i = 0; i < value.length; i++)
			h[(int) value[i]] += this.h[i];
		return new Histogram(h);
	}

	public float getValue(int i)
	{
		return value[i];
	}

	@Override
	public FloatHistogram clone()
	{
		return new FloatHistogram(this.h.clone(), this.value.clone(), minBin, maxBin);
	}
}