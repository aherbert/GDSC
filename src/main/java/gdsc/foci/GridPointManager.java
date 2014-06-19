package gdsc.foci;

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

import java.util.LinkedList;
import java.util.List;

/**
 * Stores a set of GridPoints within a grid arrangement at a given resolution. Allows comparison of a coordinate with
 * any point within the sampling resolution to locate the highest unassigned grid point.
 * 
 * Currently only supports a 2D grid.
 */
public class GridPointManager
{
	private List<GridPoint> allPoints;
	@SuppressWarnings("rawtypes")
	private List[][] grid;
	private int resolution;
	private int minX = Integer.MAX_VALUE;
	private int minY = Integer.MAX_VALUE;
	private int searchMode = 0;

	/**
	 * Define the search modes for the {@link #findUnassignedPoint(int, int)} method
	 */
	public static final String[] SEARCH_MODES = new String[] { "Highest", "Closest" };
	
	public static final int HIGHEST = 0;
	public static final int CLOSEST = 1;

	public GridPointManager(List<GridPoint> points, int resolution) throws GridException
	{
		this.resolution = resolution;
		this.allPoints = points;
		initialiseGrid();
	}

	private void initialiseGrid() throws GridException
	{
		// Find the minimum and maximum x,y
		int maxX = 0;
		int maxY = maxX;
		for (GridPoint p : allPoints)
		{
			if (p.getX() < minX)
				minX = p.getX();
			if (p.getX() > maxX)
				maxX = p.getX();
			if (p.getY() < minY)
				minY = p.getY();
			if (p.getY() > maxY)
				maxY = p.getY();
		}

		if (minX < 0 || minY < 0)
			throw new GridException("Minimum grid coordinates must not be negative (x" + minX + ",y" + minY + ")");

		int xBlocks = getXBlock(maxX) + 1;
		int yBlocks = getYBlock(maxY) + 1;

		if (xBlocks > 500 || yBlocks > 500)
			throw new GridException("Maximum number of grid blocks exceeded, please increase the resolution parameter");
		if (xBlocks <= 0 || yBlocks <= 0)
			throw new GridException("No coordinates to add to the grid");

		grid = new List[xBlocks][yBlocks];

		// Assign points
		for (GridPoint p : allPoints)
		{
			addToGrid(p);
		}
	}

	private int getXBlock(int x)
	{
		return (x - minX) / resolution;
	}

	private int getYBlock(int y)
	{
		return (y - minY) / resolution;
	}

	private void addToGrid(GridPoint p)
	{
		int xBlock = getXBlock(p.getX());
		int yBlock = getYBlock(p.getY());
		@SuppressWarnings("unchecked")
		LinkedList<GridPoint> points = (LinkedList<GridPoint>) grid[xBlock][yBlock];
		if (points == null)
		{
			points = new LinkedList<GridPoint>();
			grid[xBlock][yBlock] = points;
		}

		p.setAssigned(false);
		points.add(p);
	}

	/**
	 * Resets the assigned flag on all the points
	 */
	public void resetAssigned()
	{
		for (GridPoint p : allPoints)
		{
			p.setAssigned(false);
		}
	}

	/**
	 * Find the unassigned point using the current search mode
	 * If a point is found it will have its assigned flag set to true.
	 * 
	 * @param xCoord
	 * @param yCoord
	 * @return The GridPoint (or null)
	 */
	public GridPoint findUnassignedPoint(int xCoord, int yCoord)
	{
		switch (searchMode)
		{
			case CLOSEST:
				return findClosestUnassignedPoint(xCoord, yCoord);
			default:
				return findHighestUnassignedPoint(xCoord, yCoord);
		}
	}

	/**
	 * Find the highest assigned point within the sampling resolution from the given coordinates.
	 * 
	 * @param xCoord
	 * @param yCoord
	 * @return The GridPoint (or null)
	 */
	public GridPoint findHighestAssignedPoint(int xCoord, int yCoord)
	{
		return findHighest(xCoord, yCoord, true);
	}
	
	/**
	 * Find the highest unassigned point within the sampling resolution from the given coordinates.
	 * If a point is found it will have its assigned flag set to true.
	 * 
	 * @param xCoord
	 * @param yCoord
	 * @return The GridPoint (or null)
	 */
	public GridPoint findHighestUnassignedPoint(int xCoord, int yCoord)
	{
		GridPoint point = findHighest(xCoord, yCoord, false);

		if (point != null)
			point.setAssigned(true);

		return point;
	}
	
	/**
	 * Find the highest point within the sampling resolution from the given coordinates with the specified assigned status.
	 * 
	 * @param xCoord
	 * @param yCoord
	 * @return The GridPoint (or null)
	 */
	public GridPoint findHighest(int xCoord, int yCoord, boolean assigned)
	{
		GridPoint point = null;

		int xBlock = getXBlock(xCoord);
		int yBlock = getYBlock(yCoord);

		double resolution2 = resolution * resolution;
		if (!assigned)
		{
			// Use closest assigned peak to set the resolution for the unassigned search
    		GridPoint closestPoint = findClosestAssignedPoint(xCoord, yCoord);
    		if (closestPoint != null)
    			resolution2 = closestPoint.distance2(xCoord, yCoord);
		}

		// Check all surrounding blocks for highest unassigned point
		int maxValue = Integer.MIN_VALUE;
		for (int x = Math.max(0, xBlock - 1); x <= Math.min(grid.length - 1, xBlock + 1); x++)
		{
			for (int y = Math.max(0, yBlock - 1); y <= Math.min(grid[0].length - 1, yBlock + 1); y++)
			{
				if (grid[x][y] != null)
				{
					@SuppressWarnings("unchecked")
					LinkedList<GridPoint> points = (LinkedList<GridPoint>) grid[x][y];

					for (GridPoint p : points)
					{
						if (p.isAssigned() == assigned)
						{
							if (p.distance2(xCoord, yCoord) < resolution2)
							{
								//IJ.log(String.format("  x%d,y%d (%d) = %g", p.getX(), p.getY(), p.getValue(), p.distance(xCoord, yCoord)));
								if (maxValue < p.getValue())
								{
									maxValue = p.getValue();
									point = p;
								}
							}
						}
					}
				}
			}
		}

		return point;
	}

	/**
	 * Find the assigned point that matches the given coordinates.
	 * 
	 * @param xCoord
	 * @param yCoord
	 * @return The GridPoint (or null)
	 */
	public GridPoint findExactAssignedPoint(int xCoord, int yCoord)
	{
		return findExact(xCoord, yCoord, true);
	}

	/**
	 * Find the unassigned point that matches the given coordinates.
	 * If a point is found it will have its assigned flag set to true.
	 * 
	 * @param xCoord
	 * @param yCoord
	 * @return The GridPoint (or null)
	 */
	public GridPoint findExactUnassignedPoint(int xCoord, int yCoord)
	{
		GridPoint point = findExact(xCoord, yCoord, false);

		if (point != null)
			point.setAssigned(true);

		return point;
	}

	/**
	 * Find the point that matches the given coordinates with the specified assigned status.
	 * 
	 * @param xCoord
	 * @param yCoord
	 * @param assigned
	 * @return The GridPoint (or null)
	 */
	public GridPoint findExact(int xCoord, int yCoord, boolean assigned)
	{
		int xBlock = getXBlock(xCoord);
		int yBlock = getYBlock(yCoord);

		int x = Math.min(grid.length - 1, Math.max(0, xBlock));
		int y = Math.min(grid[0].length - 1, Math.max(0, yBlock));

		if (grid[x][y] != null)
		{
			@SuppressWarnings("unchecked")
			LinkedList<GridPoint> points = (LinkedList<GridPoint>) grid[x][y];

			for (GridPoint p : points)
			{
				if (p.isAssigned() == assigned && p.getX() == xCoord && p.getY() == yCoord)
				{
					return p;
				}
			}
		}

		return null;
	}
	
	/**
	 * Find the closest assigned point within the sampling resolution from the given coordinates
	 * 
	 * @param xCoord
	 * @param yCoord
	 * @return The GridPoint (or null)
	 */
	public GridPoint findClosestAssignedPoint(int xCoord, int yCoord)
	{
		return findClosest(xCoord, yCoord, true);
	}

	/**
	 * Find the closest unassigned point within the sampling resolution from the given coordinates.
	 * If a point is found it will have its assigned flag set to true.
	 * 
	 * @param xCoord
	 * @param yCoord
	 * @return The GridPoint (or null)
	 */
	public GridPoint findClosestUnassignedPoint(int xCoord, int yCoord)
	{
		GridPoint point = findClosest(xCoord, yCoord, false);

		if (point != null)
			point.setAssigned(true);

		return point;
	}

	/**
	 * Find the closest point within the sampling resolution from the given coordinates with the specified assigned
	 * status.
	 * 
	 * @param xCoord
	 * @param yCoord
	 * @param assigned
	 * @return The GridPoint (or null)
	 */
	public GridPoint findClosest(int xCoord, int yCoord, boolean assigned)
	{
		GridPoint point = null;

		int xBlock = getXBlock(xCoord);
		int yBlock = getYBlock(yCoord);

		double resolution2 = resolution * resolution;
		for (int x = Math.max(0, xBlock - 1); x <= Math.min(grid.length - 1, xBlock + 1); x++)
		{
			for (int y = Math.max(0, yBlock - 1); y <= Math.min(grid[0].length - 1, yBlock + 1); y++)
			{
				if (grid[x][y] != null)
				{
					@SuppressWarnings("unchecked")
					LinkedList<GridPoint> points = (LinkedList<GridPoint>) grid[x][y];

					for (GridPoint p : points)
					{
						if (p.isAssigned() == assigned)
						{
							final double d2 = p.distance2(xCoord, yCoord); 
							if (d2 < resolution2)
							{
								resolution2 = d2;
								point = p;
							}
						}
					}
				}
			}
		}

		return point;
	}

	/**
	 * @return the resolution
	 */
	public int getResolution()
	{
		return resolution;
	}

	/**
	 * @param searchMode
	 *            the searchMode to set (see {@link #SEARCH_MODES} )
	 */
	public void setSearchMode(int searchMode)
	{
		this.searchMode = searchMode;
	}

	/**
	 * @return the searchMode
	 */
	public int getSearchMode()
	{
		return searchMode;
	}
}