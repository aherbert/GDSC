package gdsc.analytics;

/*
 * <ul>
 * <li>Copyright (c) 2010 Daniel Murphy
 * <li>Copyright (c) 2016 Alex Herbert
 * </ul>
 * 
 * Permission is hereby granted, free of charge, to any person obtaining
 * a copy of this software and associated documentation files (the
 * "Software"), to deal in the Software without restriction, including
 * without limitation the rights to use, copy, modify, merge, publish,
 * distribute, sublicense, and/or sell copies of the Software, and to
 * permit persons to whom the Software is furnished to do so, subject to
 * the following conditions:
 * <p>
 * The above copyright notice and this permission notice shall be
 * included in all copies or substantial portions of the Software.
 * <p>
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND,
 * EXPRESS OR IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF
 * MERCHANTABILITY, FITNESS FOR A PARTICULAR PURPOSE AND
 * NONINFRINGEMENT. IN NO EVENT SHALL THE AUTHORS OR COPYRIGHT HOLDERS BE
 * LIABLE FOR ANY CLAIM, DAMAGES OR OTHER LIABILITY, WHETHER IN AN ACTION
 * OF CONTRACT, TORT OR OTHERWISE, ARISING FROM, OUT OF OR IN CONNECTION
 * WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE SOFTWARE.
 *
 * @see https://code.google.com/archive/p/jgoogleanalyticstracker/
 */

import java.security.SecureRandom;

/**
 * Represent the Google Analytics session data for a visitor.
 * 
 * @see http://www.cardinalpath.com/ga-basics-the-structure-of-cookie-values/
 * @author Alex Herbert
 */
public class SessionData
{
	/**
	 * The unique vistor ID
	 */
	protected int visitorId;
	/**
	 * Timestamp of the first visit
	 */
	protected long initial;
	/**
	 * Timestamp of the previous visit
	 */
	protected long previous;
	/**
	 * Timestamp of the current visit
	 */
	protected long current;
	/**
	 * Timestamp of the latest call to refresh the session.
	 * 
	 * @see #refresh()
	 */
	protected long latest;
	/**
	 * Session number
	 */
	protected int sessionNumber;
	/**
	 * The number of times the session has been refreshed
	 */
	protected int count;
	/**
	 * Google sessions timeout after 30 minutes of inactivity
	 */
	protected final int TIMEOUT = 30 * 60;

	private SessionStore sessionStore = null;

	/**
	 * Create a new session
	 * 
	 * @param visitorId
	 *            The visitor Id
	 */
	private SessionData(int visitorId)
	{
		this.visitorId = visitorId;
		initial = previous = current = latest = timestamp();
		this.count = 0;
		this.sessionNumber = 1;
		this.sessionStore = null;
	}

	/**
	 * Get the number of seconds since the epoch (midnight, January 1, 1970 UTC)
	 * 
	 * @return The timestamp in seconds
	 */
	public static long timestamp()
	{
		return System.currentTimeMillis() / 1000L;
	}

	/**
	 * Create a new session
	 * 
	 * @param sessionStore
	 *            The session store used to load/save the state
	 */
	private SessionData(SessionStore sessionStore) throws IllegalArgumentException
	{
		long[] data = sessionStore.load();
		if (data == null || data.length < 7)
			throw new IllegalArgumentException("Invalid session data");
		this.visitorId = getInt(data[0], Integer.MIN_VALUE);
		this.initial = data[1];
		this.previous = data[2];
		this.current = data[3];
		this.latest = data[4];
		this.count = getInt(data[5], 0);
		this.sessionNumber = getInt(data[6], 0);
		this.sessionStore = sessionStore;

		// Check the timestamps make sense
		if (latest < current)
			throw new IllegalArgumentException("Latest timestamp before current");
		if (current < previous)
			throw new IllegalArgumentException("Current timestamp before previous");
		if (previous < initial)
			throw new IllegalArgumentException("Previous timestamp before initial");
	}

	/**
	 * Check the long can be cast to an integer without truncation
	 * 
	 * @param l
	 *            A long
	 * @param min
	 *            The minimum
	 * @return The integer value
	 * @throws IllegalArgumentException
	 */
	private static int getInt(long l, int min) throws IllegalArgumentException
	{
		if (l <= Integer.MAX_VALUE && l >= min)
			return (int) l;
		throw new IllegalArgumentException("Session data is not a valid integer");
	}

	/**
	 * Initialises a new session data, with new random visitor id
	 */
	public static SessionData newSessionData()
	{
		final int visitorId = (new SecureRandom().nextInt() & 0x7FFFFFFF);
		return new SessionData(visitorId);
	}

	/**
	 * Initialises a new session data using the session store to load the session, else create a new session with random
	 * visitor id
	 */
	public static SessionData newSessionData(SessionStore sessionStore)
	{
		if (sessionStore != null)
		{
			try
			{
				return new SessionData(sessionStore);
			}
			catch (IllegalArgumentException e)
			{
				// Ignore this and create a default new session
			}
		}
		SessionData sessionData = newSessionData();
		// Add the session store and save the new session 
		sessionData.sessionStore = sessionStore;
		sessionData.save();
		return sessionData;
	}

	/**
	 * Save the session state. This should be called whenever the state changes.
	 */
	private void save()
	{
		if (sessionStore != null)
			sessionStore.save(new long[] { visitorId, initial, previous, current, latest, count, sessionNumber });
	}

	/**
	 * Get the visitor Id
	 * 
	 * @return The visitor Id
	 */
	public int getVisitorId()
	{
		return visitorId;
	}

	/**
	 * Get the start time of the first ever session
	 * 
	 * @return Timestamp of the first visit
	 */
	public long getInitial()
	{
		return initial;
	}

	/**
	 * Get the start time of the previous session
	 * 
	 * @return Timestamp of the previous visit
	 */
	public long getPrevious()
	{
		return previous;
	}

	/**
	 * Get the start time of the current session
	 * 
	 * @return Timestamp of the current visit
	 */
	public long getCurrent()
	{
		return current;
	}

	/**
	 * Get the latest timestamp of the current session (time of last call to {@link #refresh()} or the time the session
	 * was created)
	 * 
	 * @return Latest timestamp
	 */
	public long getLatest()
	{
		return latest;
	}

	/**
	 * Get the session number
	 * 
	 * @return The session number
	 */
	public int getSessionNumber()
	{
		return sessionNumber;
	}

	/**
	 * Get the hit count for the session. This is equivalent to the number of times {@link #refresh()} is called.
	 * 
	 * @return The count of times the session has been refreshed
	 */
	public int getCount()
	{
		// Check if not yet initialised.
		// This allows a new session object to have a correct hit count even if the 
		// refresh() function has not been called, i.e.
		// 0 or 1 calls to refresh() will produce a count of 1
		// N calls to refresh() will produce a count of N
		// This allows refresh() to be called when a session has just been created.
		if (count == 0)
			count = 1;
		return count;
	}

	/**
	 * Refresh the session and increment the hit count. If the session has expired then the session number will be
	 * incremented and the current and previous timestamps will be updated. The hit counter will be reset.
	 */
	public void refresh()
	{
		count++;

		// Get the current session expire time
		final long expires = latest + TIMEOUT;
		// Get the current time.
		// Only do this if the count is above 1, i.e. do not update the 
		// timestamp if the session has just been created
		if (count > 1)
			latest = timestamp();
		// Check if the session has expired
		if (latest > expires)
		{
			newSession(latest);
		}
		else
		{
			save();
		}
	}

	/**
	 * Increment the session number to start a new session
	 */
	public void newSession()
	{
		newSession(timestamp());
	}

	/**
	 * Increment the session number to start a new session. This resets the hit counter.
	 * 
	 * @param now
	 *            The current timpstamp for the new session
	 */
	protected void newSession(long now)
	{
		// Previous stores the start time of the last session 
		previous = current;
		current = latest = now;
		this.sessionNumber++;
		// Reset the hit counter
		this.count = 0;
		save();
	}
}