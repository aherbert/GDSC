GDSC ImageJ Plugins
===================

The Genome Damage and Stability Centre (GDSC) plugins are a collection of
analysis programs for microscopy images including colocalisation analysis and
peak finding.

[![License: GPL v3](https://img.shields.io/badge/License-GPLv3-blue.svg)](https://www.gnu.org/licenses/gpl-3.0)
[![Build Status](https://travis-ci.com/aherbert/gdsc.svg?branch=master)](https://travis-ci.com/aherbert/gdsc)
[![Coverage Status](https://coveralls.io/repos/github/aherbert/gdsc/badge.svg?branch=master)](https://coveralls.io/github/aherbert/gdsc?branch=master)

Find Foci
---------

The Find Foci plugins allow the identification of peak intensity regions within
2D and 3D images. The tools provide: the automated and semi-automated labelling
of peaks; comparison of marked points between images; and alignment of manually
marked points to peak maxima.

See the [FindFoci User Manual](FindFoci.odt) for full details.

Colocalisation Analysis
-----------------------

The GDSC Colocalisation plugins provide various tools to perform colocalisation
analysis. The tools provide: thresholding for N-dimensional images for signal
identification; correlation and overlap coefficient analysis; and
colocalisation significance testing.

See the [Colocalisation User Manual](Colocalisation.odt) for full details.

Utility Plugins
---------------

The GDSC plugins provide various utility tools for image analysis. The tools 
include: thresholding and mask generation; difference of Gaussians for 
contrast enhancement; stack synchronisation for simultaneous image viewing; 
and image alignment.


Install
-------

The GDSC plugins are distributed using an ImageJ2/Fiji update site. 

To install the plugins using Fiji (an ImageJ distribution) just follow the
instructions [How_to_follow_a_3rd_party_update_site](http://fiji.sc/How_to_follow_a_3rd_party_update_site) 
and add the GDSC update site. All the plugins will appear under the 
'Plugins > GDSC' menu.


Installation from source
------------------------

The source code is accessed using git and built using Maven. 

The code depends on the gdsc-analytics, gdsc-test and gdsc-core artifacts so 
you will have to install these to your local Maven repository before building:

1. Clone the required repositories

        git clone https://github.com/aherbert/gdsc-analytics.git
        git clone https://github.com/aherbert/gdsc-test.git
        git clone https://github.com/aherbert/gdsc-core.git
        git clone https://github.com/aherbert/gdsc.git

2. Build the code and install using Maven

        cd gdsc-analytics
        mvn install
        cd ..
        cd gdsc-test
        mvn install
        cd ..
        cd gdsc-core
        mvn install
        cd ..
        cd gdsc
        mvn package

	This will produce a gdsc_-[VERSION].jar file in the target directory. All 
	dependencies are copied into the target/dependencies directory.

3. Copy the gdsc_* jar into the plugins directory of ImageJ. 

4. Copy the dependencies into the plugins directory (or onto the Java
classpath). Note that the Maven package routine puts all dependencies into
the target/dependencies directory even if they are not required by the SMLM code
(it does not check what functions are actually used by the code). The libraries
you will need are:
  
        gdsc-analytics
        gdsc-core
        beansbinding
        commons-math3
        imagescience

5. The plugins will now appear under the 'Plugins > GDSC' menu in ImageJ.


Running from source
-------------------

1. Build the code

        mvn compile

2. Change to the ij directory

        cd ij

3. Using the build.xml file for Apache Ant, run ImageJ

        ant

	This will package all the compiled GDSC classes into a jar file within the
	plugins folder, copy ImageJ and the GDSC dependencies from the Maven 
	repsitory, and then launch ImageJ.

4. When finished you can remove all the created files using

        ant clean


Legal
-----

See [LICENSE](LICENSE.txt)


# About #

###### Repository name ######
GDSC ImageJ Plugins

###### Owner(s) ######
Alex Herbert

###### Institution ######
Genome Damage and Stability Centre, University of Sussex

###### URL ######
http://www.sussex.ac.uk/gdsc/intranet/microscopy/imagej/gdsc_plugins

###### Email ######
a.herbert@sussex.ac.uk

###### Description ######
The Genome Damage and Stability Centre (GDSC) plugins are a collection of
analysis programs for microscopy images including colocalisation analysis and
peak finding.
