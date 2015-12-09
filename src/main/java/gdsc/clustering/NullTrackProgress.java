package gdsc.clustering;

/*----------------------------------------------------------------------------- 
 * GDSC SMLM Software
 * 
 * Copyright (C) 2013 Alex Herbert
 * Genome Damage and Stability Centre
 * University of Sussex, UK
 * 
 * This program is free software; you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation; either version 3 of the License, or
 * (at your option) any later version.
 *---------------------------------------------------------------------------*/

/**
 * Ignore all method calls from the {@link TrackProgress} interface
 */
public class NullTrackProgress implements TrackProgress
{
	/*
	 * (non-Javadoc)
	 * 
	 * @see gdsc.utils.fitting.results.TrackProgress#progress(double)
	 */
	public void progress(double fraction)
	{
	}

	/*
	 * (non-Javadoc)
	 * 
	 * @see gdsc.utils.fitting.results.TrackProgress#progress(long, long)
	 */
	public void progress(long position, long total)
	{
	}

	/*
	 * (non-Javadoc)
	 * 
	 * @see gdsc.smlm.results.TrackProgress#log(java.lang.String, java.lang.Object[])
	 */
	public void log(String format, Object... args)
	{
	}

	/*
	 * (non-Javadoc)
	 * 
	 * @see gdsc.smlm.results.TrackProgress#status(java.lang.String, java.lang.Object[])
	 */
	public void status(String format, Object... args)
	{
	}

	/*
	 * (non-Javadoc)
	 * 
	 * @see gdsc.smlm.results.TrackProgress#isEnded()
	 */
	public boolean isEnded()
	{
		return false;
	}
}