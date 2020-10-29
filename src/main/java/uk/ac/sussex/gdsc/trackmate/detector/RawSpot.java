/*-
 * #%L
 * Genome Damage and Stability Centre ImageJ Plugins
 *
 * Software for microscopy image analysis
 * %%
 * Copyright (C) 2011 - 2020 Alex Herbert
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

package uk.ac.sussex.gdsc.trackmate.detector;

/**
 * Raw data for the Spot class.
 */
class RawSpot {
  /** The x. */
  final double x;
  /** The y. */
  final double y;
  /** The z. */
  final double z;
  /** The radius. */
  final double radius;

  /**
   * Create a new instance.
   *
   * @param x the x
   * @param y the y
   * @param z the z
   * @param radius the radius
   */
  RawSpot(double x, double y, double z, double radius) {
    this.x = x;
    this.y = y;
    this.z = z;
    this.radius = radius;
  }
}