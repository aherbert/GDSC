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

import gdsc.foci.controller.FindFociController;
import gdsc.foci.controller.ImageJController;
import gdsc.foci.gui.FindFociView;
import gdsc.foci.model.FindFociModel;
import ij.IJ;
import ij.ImageListener;
import ij.ImagePlus;
import ij.WindowManager;
import ij.plugin.PlugIn;

import java.awt.event.WindowEvent;
import java.awt.event.WindowListener;
import java.beans.PropertyChangeEvent;
import java.beans.PropertyChangeListener;
import java.io.PrintWriter;
import java.io.StringWriter;

import javax.swing.JFrame;

/**
 * Provides a permanent form front-end for the FindFoci plugin filter
 */
public class FindFociPlugin implements PlugIn, WindowListener, ImageListener, PropertyChangeListener
{
	private static FindFociView instance;
	private FindFociModel model;
	private int currentSlice = 0;

	/*
	 * (non-Javadoc)
	 * 
	 * @see ij.plugin.frame.PlugInFrame#run(java.lang.String)
	 */
	public void run(String arg)
	{
		if (WindowManager.getImageCount() < 1)
		{
			IJ.showMessage("No images opened.");
			return;
		}

		if (instance != null)
		{
			showInstance();
			return;
		}

		model = new FindFociModel();
		model.setResultsDirectory(System.getProperty("java.io.tmpdir"));
		FindFociController controller = new ImageJController(model);

		// Track when the image changes to a new slice
		ImagePlus.addImageListener(this);
		model.addPropertyChangeListener("selectedImage", this);

		IJ.showStatus("Initialising FindFoci ...");

		String errorMessage = null;
		Throwable exception = null;

		try
		{
			Class.forName("org.jdesktop.beansbinding.Property", false, this.getClass().getClassLoader());

			// it exists on the classpath
			instance = new FindFociView(model, controller);
			instance.addWindowListener(this);
			instance.setDefaultCloseOperation(JFrame.HIDE_ON_CLOSE);

			IJ.register(FindFociView.class);

			showInstance();
			IJ.showStatus("FindFoci ready");
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
			errorMessage = "Failed to find class: " + ex.getMessage() +
					"\nCheck you have beansbinding-1.2.1.jar on your classpath\n";
		}
		catch (Throwable ex)
		{
			exception = ex;
			errorMessage = ex.getMessage();
		}
		finally
		{
			if (exception != null)
			{
				StringWriter sw = new StringWriter();
				PrintWriter pw = new PrintWriter(sw);
				pw.write(errorMessage);
				pw.append('\n');
				exception.printStackTrace(pw);
				IJ.log(sw.toString());
			}
		}
	}

	private void showInstance()
	{
		WindowManager.addWindow(instance);
		instance.setVisible(true);
		instance.toFront();
	}

	public void windowOpened(WindowEvent e)
	{
	}

	public void windowClosing(WindowEvent e)
	{
		WindowManager.removeWindow(instance);
	}

	public void windowClosed(WindowEvent e)
	{
	}

	public void windowIconified(WindowEvent e)
	{
	}

	public void windowDeiconified(WindowEvent e)
	{
	}

	public void windowActivated(WindowEvent e)
	{
	}

	public void windowDeactivated(WindowEvent e)
	{
	}

	public void imageOpened(ImagePlus imp)
	{
		// Ignore
	}

	public void imageClosed(ImagePlus imp)
	{
		// Ignore
	}

	public void imageUpdated(ImagePlus imp)
	{
		if (imp == null)
			return;

		// Check if the image is the selected image in the model.
		// If the slice has changed then invalidate the model
		if (imp.getTitle().equals(model.getSelectedImage()))
		{
			int oldCurrentSlice = currentSlice;
			currentSlice = getCurrentSlice();
			if (oldCurrentSlice != currentSlice)
			{
				model.invalidate();
			}
		}
	}

	private int getCurrentSlice()
	{
		ImagePlus imp = WindowManager.getImage(model.getSelectedImage());
		return (imp != null) ? imp.getCurrentSlice() : 0;
	}

	public void propertyChange(PropertyChangeEvent evt)
	{
		// Store the slice for the image when it changes.
		if (evt.getPropertyName().equals("selectedImage"))
		{
			currentSlice = getCurrentSlice();
		}
	}
}
