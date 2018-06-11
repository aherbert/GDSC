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
package ij.gui;

import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics;
import java.awt.Polygon;
import java.util.Arrays;

import ij.ImagePlus;
import ij.Prefs;
import ij.process.FloatPolygon;
import ij.util.Java2;

/**
 * Extend the PointRoi class to allow custom number labels for each point
 */
public class PointRoi2 extends PointRoi
{
	private static final long serialVersionUID = -1054219408481333977L;
	private static Font font;
	private static int fontSize = 9;
	private double saveMag;
	private boolean hideLabels;
	private int[] labels = null;

	public PointRoi2(int[] ox, int[] oy, int points)
	{
		super(ox, oy, points);
	}

	/** Creates a new PointRoi2 using the specified float arrays of offscreen coordinates. */
	public PointRoi2(float[] ox, float[] oy, int points)
	{
		super(ox, oy, points);
	}

	/** Creates a new PointRoi2 from a FloatPolygon. */
	public PointRoi2(FloatPolygon poly)
	{
		super(poly);
	}

	/** Creates a new PointRoi2 from a Polygon. */
	public PointRoi2(Polygon poly)
	{
		super(poly);
	}

	/** Creates a new PointRoi2 using the specified offscreen int coordinates. */
	public PointRoi2(int ox, int oy)
	{
		super(ox, oy);
	}

	/** Creates a new PointRoi2 using the specified offscreen double coordinates. */
	public PointRoi2(double ox, double oy)
	{
		super(ox, oy);
	}

	/** Creates a new PointRoi2 using the specified screen coordinates. */
	public PointRoi2(int sx, int sy, ImagePlus imp)
	{
		super(sx, sy, imp);
	}

	/** Draws the points on the image. */
	@Override
	public void draw(Graphics g)
	{
		updatePolygon();
		if (ic != null)
			mag = ic.getMagnification();
		int size2 = HANDLE_SIZE / 2;
		if (!Prefs.noPointLabels && !hideLabels)
		{
			fontSize = 9;
			if (mag > 1.0)
				fontSize = (int) (((mag - 1.0) / 3.0 + 1.0) * 9.0);
			if (fontSize > 18)
				fontSize = 18;
			if (font == null || mag != saveMag)
				font = new Font("SansSerif", Font.PLAIN, fontSize);
			g.setFont(font);
			if (fontSize > 9)
				Java2.setAntialiasedText(g, true);
			saveMag = mag;
		}
		for (int i = 0; i < nPoints; i++)
		{
			final int n = (labels != null) ? labels[i] : i + 1;
			drawPoint(g, xp2[i] - size2, yp2[i] - size2, n);
		}
		if (updateFullWindow)
		{
			updateFullWindow = false;
			imp.draw();
		}
	}

	@Override
	void drawPoint(Graphics g, int x, int y, int n)
	{
		g.setColor(fillColor != null ? fillColor : Color.white);
		g.drawLine(x - 4, y + 2, x + 8, y + 2);
		g.drawLine(x + 2, y - 4, x + 2, y + 8);
		g.setColor(strokeColor != null ? strokeColor : ROIColor);
		g.fillRect(x + 1, y + 1, 3, 3);
		if (!Prefs.noPointLabels && !hideLabels && (nPoints > 1 || labels != null))
			g.drawString("" + n, x + 6, y + fontSize + 4);
		g.setColor(Color.black);
		g.drawRect(x, y, 4, 4);
	}

	/*
	 * (non-Javadoc)
	 * 
	 * @see ij.gui.PointRoi#setHideLabels(boolean)
	 */
	@Override
	public void setHideLabels(boolean hideLabels)
	{
		this.hideLabels = hideLabels;
	}

	/**
	 * Set the labels to use for each point. Default uses numbers counting from 1.
	 * 
	 * @param labels
	 */
	public void setLabels(int[] labels)
	{
		if (labels != null && labels.length >= nPoints)
			this.labels = Arrays.copyOf(labels, nPoints);
	}
}
