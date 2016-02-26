package gdsc.analytics;

/*
 * <ul>
 * <li>Copyright (c) 2010 Daniel Murphy, Stefan Brozinski
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

import java.net.HttpURLConnection;
import java.net.InetSocketAddress;
import java.net.Proxy;
import java.net.Proxy.Type;
import java.net.SocketAddress;
import java.net.URL;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Queue;
import java.util.Scanner;
import java.util.regex.MatchResult;

/**
 * Common tracking calls are implemented as methods, but if you want to control
 * what data to send, then use {@link #makeCustomRequest(RequestData)}.
 * If you are making custom calls, the only requirements are:
 * <ul>
 * <li>{@link RequestData#setPageURL(String)} must be populated</li>
 * </ul>
 * See the <a
 * href=http://code.google.com/intl/en-US/apis/analytics/docs/tracking/gaTrackingTroubleshooting.html#gifParameters>
 * Google Troubleshooting Guide</a> for more info on the tracking parameters (although it doesn't seem to be fully
 * updated).
 * <p>
 * The tracker can operate in three modes:
 * <ul>
 * <li>synchronous mode: The HTTP request is sent to GA immediately, before the track method returns. This may slow your
 * application down if GA doesn't respond fast.
 * <li>multi-thread mode: Each track method call creates a new short-lived thread that sends the HTTP request to GA in
 * the background and terminates.
 * <li>single-thread mode (the default): The track method stores the request in a FIFO and returns immediately. A single
 * long-lived background thread consumes the FIFO content and sends the HTTP requests to GA.
 * </ul>
 * </p>
 * <p>
 * To halt the background thread safely, use the call {@link #stopBackgroundThread(long)}, where the parameter is the
 * timeout to wait for any remaining queued tracking calls to be made. Keep in mind that if new tracking requests are
 * made after the thread is stopped, they will just be stored in the queue, and will not be sent to GA until the thread
 * is started again with {@link #startBackgroundThread()} (This is assuming you are in single-threaded mode to begin
 * with).
 * </p>
 * <p>
 * Note: This class has been forked from the JGoogleAnalyticsTracker project and modified by Alex Herbert to alter the
 * data sent to Google Analytics and remove the slfj dependency. The architecture for dispatching messages is unchanged.
 * </p>
 * 
 * @author Daniel Murphy, Stefan Brozinski, Alex Herbert
 */
public class JGoogleAnalyticsTracker
{
	public static enum DispatchMode
	{
		/**
		 * Each tracking call will wait until the http request
		 * completes before returning
		 */
		SYNCHRONOUS,
		/**
		 * Each tracking call spawns a new thread to make the http request
		 */
		MULTI_THREAD,
		/**
		 * Each tracking request is added to a queue, and a single dispatch thread makes the requests.
		 */
		SINGLE_THREAD
	}

	/**
	 * Store the data for the HTTP Get method
	 * 
	 * @author a.herbert@sussex.ac.uk
	 */
	private class HTTPData
	{
		final String url;
		final List<Entry<String, String>> headers;

		HTTPData(String url, List<Entry<String, String>> headers)
		{
			this.url = url;
			this.headers = headers;
		}
	}

	private static Logger logger = new Logger();
	private static final ThreadGroup asyncThreadGroup = new ThreadGroup("Async Google Analytics Threads");
	private static long asyncThreadsRunning = 0;
	private static Proxy proxy = Proxy.NO_PROXY;
	private static Queue<HTTPData> fifo = new LinkedList<HTTPData>();
	private static Thread backgroundThread = null; // the thread used in 'queued' mode.
	private static boolean backgroundThreadMayRun = false;

	static
	{
		asyncThreadGroup.setMaxPriority(Thread.MIN_PRIORITY);
		asyncThreadGroup.setDaemon(true);
	}

	public static enum GoogleAnalyticsVersion
	{
		V_5_6_7
	}

	private GoogleAnalyticsVersion version;
	private ClientData clientData;
	private IGoogleAnalyticsURLBuilder builder;
	private DispatchMode mode;
	private boolean enabled;

	public JGoogleAnalyticsTracker(ClientData clientData, GoogleAnalyticsVersion version)
	{
		this(clientData, version, DispatchMode.SINGLE_THREAD);
	}

	public JGoogleAnalyticsTracker(ClientData clientData, GoogleAnalyticsVersion version, DispatchMode dispatchMode)
	{
		this.version = version;
		this.clientData = clientData;
		createBuilder();
		enabled = true;
		setDispatchMode(dispatchMode);
	}

	/**
	 * Sets the dispatch mode
	 * 
	 * @see DispatchMode
	 * @param mode
	 *            the mode to to put the tracker in. If this is null, the tracker
	 *            defaults to {@link DispatchMode#SINGLE_THREAD}
	 */
	public void setDispatchMode(DispatchMode mode)
	{
		if (mode == null)
		{
			mode = DispatchMode.SINGLE_THREAD;
		}
		if (mode == DispatchMode.SINGLE_THREAD)
		{
			startBackgroundThread();
		}
		this.mode = mode;
	}

	/**
	 * Gets the current dispatch mode. Default is {@link DispatchMode#SINGLE_THREAD}.
	 * 
	 * @see DispatchMode
	 * @return
	 */
	public DispatchMode getDispatchMode()
	{
		return mode;
	}

	/**
	 * Convenience method to check if the tracker is in synchronous mode.
	 * 
	 * @return
	 */
	public boolean isSynchronous()
	{
		return mode == DispatchMode.SYNCHRONOUS;
	}

	/**
	 * Convenience method to check if the tracker is in single-thread mode
	 * 
	 * @return
	 */
	public boolean isSingleThreaded()
	{
		return mode == DispatchMode.SINGLE_THREAD;
	}

	/**
	 * Convenience method to check if the tracker is in multi-thread mode
	 * 
	 * @return
	 */
	public boolean isMultiThreaded()
	{
		return mode == DispatchMode.MULTI_THREAD;
	}

	/**
	 * Resets the session cookie.
	 */
	public void resetSession()
	{
		clientData.getSessionData().newSession();
	}

	/**
	 * Sets if the api dispatches tracking requests.
	 * 
	 * @param enabled
	 */
	public void setEnabled(boolean enabled)
	{
		this.enabled = enabled;
	}

	/**
	 * If the api is dispatching tracking requests (default of true).
	 * 
	 * @return
	 */
	public boolean isEnabled()
	{
		return enabled;
	}

	/**
	 * Define the proxy to use for all GA tracking requests.
	 * <p>
	 * Call this static method early (before creating any tracking requests).
	 * 
	 * @param proxy
	 *            The proxy to use
	 */
	public static void setProxy(Proxy proxy)
	{
		JGoogleAnalyticsTracker.proxy = (proxy != null) ? proxy : Proxy.NO_PROXY;
	}

	/**
	 * Define the proxy to use for all GA tracking requests.
	 * <p>
	 * Call this static method early (before creating any tracking requests).
	 * 
	 * @param proxyAddr
	 *            "addr:port" of the proxy to use; may also be given as URL ("http://addr:port/").
	 */
	public static void setProxy(String proxyAddr)
	{
		if (proxyAddr != null)
		{
			Scanner s = new Scanner(proxyAddr);

			// Split into "proxyAddr:proxyPort".
			proxyAddr = null;
			int proxyPort = 8080;
			try
			{
				s.findInLine("(http://|)([^:/]+)(:|)([0-9]*)(/|)");
				MatchResult m = s.match();

				if (m.groupCount() >= 2)
				{
					proxyAddr = m.group(2);
				}

				if ((m.groupCount() >= 4) && (!m.group(4).isEmpty()))
				{
					proxyPort = Integer.parseInt(m.group(4));
				}
			}
			finally
			{
				s.close();
			}

			if (proxyAddr != null)
			{
				SocketAddress sa = new InetSocketAddress(proxyAddr, proxyPort);
				setProxy(new Proxy(Type.HTTP, sa));
			}
		}
	}

	/**
	 * Wait for background tasks to complete.
	 * <p>
	 * This works in queued and asynchronous mode.
	 * 
	 * @param timeoutMillis
	 *            The maximum number of milliseconds to wait.
	 */
	public static void completeBackgroundTasks(long timeoutMillis)
	{
		boolean fifoEmpty = false;
		boolean asyncThreadsCompleted = false;

		long absTimeout = System.currentTimeMillis() + timeoutMillis;
		while (System.currentTimeMillis() < absTimeout)
		{
			synchronized (fifo)
			{
				fifoEmpty = (fifo.size() == 0);
			}

			synchronized (JGoogleAnalyticsTracker.class)
			{
				asyncThreadsCompleted = (asyncThreadsRunning == 0);
			}

			if (fifoEmpty && asyncThreadsCompleted)
			{
				break;
			}

			try
			{
				Thread.sleep(100);
			}
			catch (InterruptedException e)
			{
				break;
			}
		}
	}

	/**
	 * Makes a custom tracking request based from the given data.
	 * 
	 * @param requestData
	 * @throws NullPointerException
	 *             if requestData is null or if the URL builder is null
	 */
	public synchronized void makeCustomRequest(RequestData requestData)
	{
		if (!enabled)
		{
			logger.debug("Ignoring tracking request, enabled is false");
			return;
		}
		if (requestData == null)
		{
			throw new NullPointerException("Data cannot be null");
		}
		if (builder == null)
		{
			throw new NullPointerException("Class was not initialized");
		}
		final String url = builder.buildURL(requestData);

		switch (mode)
		{
			case MULTI_THREAD:
				Thread t = new Thread(asyncThreadGroup, "AnalyticsThread-" + asyncThreadGroup.activeCount())
				{
					public void run()
					{
						synchronized (JGoogleAnalyticsTracker.class)
						{
							asyncThreadsRunning++;
						}
						try
						{
							dispatchRequest(url, clientData.getHeaders());
						}
						finally
						{
							synchronized (JGoogleAnalyticsTracker.class)
							{
								asyncThreadsRunning--;
							}
						}
					}
				};
				t.setDaemon(true);
				t.start();
				break;

			case SYNCHRONOUS:
				dispatchRequest(url, clientData.getHeaders());
				break;

			case SINGLE_THREAD:
			default: // in case it's null, we default to the single-thread
				synchronized (fifo)
				{
					fifo.add(new HTTPData(url, clientData.getHeaders()));
					fifo.notify();
				}
				if (!backgroundThreadMayRun)
				{
					logger.error(
							"A tracker request has been added to the queue but the background thread isn't running.");
				}
				break;
		}
	}

	private static void dispatchRequest(String requestURL, List<Entry<String, String>> headers)
	{
		try
		{
			final URL url = new URL(requestURL);
			final HttpURLConnection connection = (HttpURLConnection) url.openConnection(proxy);
			connection.setRequestMethod("GET");
			connection.setInstanceFollowRedirects(true);
			if (headers != null)
			{
				for (Map.Entry<String, String> entry : headers)
				{
					connection.setRequestProperty(entry.getKey(), entry.getValue());
				}
			}
			connection.connect();
			final int responseCode = connection.getResponseCode();
			if (responseCode != HttpURLConnection.HTTP_OK)
			{
				logger.error("JGoogleAnalyticsTracker: Error requesting url '%s', received response code %d",
						requestURL, responseCode);
			}
			else
			{
				logger.debug("JGoogleAnalyticsTracker: Tracking success for url '%s'", requestURL);
			}
		}
		catch (Exception e)
		{
			logger.error("Error making tracking request: %s", e.getMessage());
		}
	}

	private void createBuilder()
	{
		switch (version)
		{
			case V_5_6_7:
			default:
				builder = new GoogleAnalyticsURLBuilder(clientData);
				break;
		}
	}

	/**
	 * If the background thread for 'queued' mode is not running, start it now.
	 */
	private synchronized static void startBackgroundThread()
	{
		if (backgroundThread == null)
		{
			backgroundThreadMayRun = true;
			backgroundThread = new Thread(asyncThreadGroup, "AnalyticsBackgroundThread")
			{
				public void run()
				{
					logger.debug("AnalyticsBackgroundThread started");
					while (backgroundThreadMayRun)
					{
						try
						{
							HTTPData httpData = null;

							synchronized (fifo)
							{
								if (fifo.isEmpty())
								{
									fifo.wait();
								}

								if (!fifo.isEmpty())
								{
									// Get a reference to the oldest element in the FIFO, but leave it in the FIFO until it is processed.
									httpData = fifo.peek();
								}
							}

							if (httpData != null)
							{
								try
								{
									dispatchRequest(httpData.url, httpData.headers);
								}
								finally
								{
									// Now that we have completed the HTTP request to GA, remove the element from the FIFO.
									synchronized (fifo)
									{
										fifo.poll();
									}
								}
							}
						}
						catch (Exception e)
						{
							logger.error("Got exception from dispatch thread: %s", e.getMessage());
						}
					}
				}
			};

			// Don't prevent the application from terminating.
			// Use completeBackgroundTasks() before exit if you want to ensure 
			// that all pending GA requests are sent. 
			backgroundThread.setDaemon(true);
			backgroundThread.start();
		}
	}

	/**
	 * Stop the long-lived background thread.
	 * <p>
	 * This method is needed for debugging purposes only. Calling it in an application is not really required: The
	 * background thread will terminate automatically when the application exits.
	 * 
	 * @param timeoutMillis
	 *            If nonzero, wait for thread completion before returning.
	 */
	public static void stopBackgroundThread(long timeoutMillis)
	{
		backgroundThreadMayRun = false;
		synchronized (fifo)
		{
			fifo.notify();
		}
		if ((backgroundThread != null) && (timeoutMillis > 0))
		{
			try
			{
				backgroundThread.join(timeoutMillis);
			}
			catch (InterruptedException e)
			{
			}
			backgroundThread = null;
		}
	}

	/**
	 * Track a page
	 * 
	 * @param pageUrl
	 *            The page URL (must not be null)
	 * @param pageTitle
	 *            The page title
	 */
	public void page(String pageUrl, String pageTitle)
	{
		RequestData data = new RequestData();
		data.setPageURL(pageUrl);
		data.setPageTitle(pageTitle);
		makeCustomRequest(data);
	}

	/**
	 * Get the logger
	 * 
	 * @return the logger
	 */
	public static Logger getLogger()
	{
		return logger;
	}

	/**
	 * Set the logger
	 * 
	 * @param logger
	 *            the logger to set
	 */
	public static void setLogger(Logger logger)
	{
		// If null set to the default (null) logger
		if (logger == null)
			logger = new Logger();
		JGoogleAnalyticsTracker.logger = logger;
	}
}
