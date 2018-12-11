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

package uk.ac.sussex.gdsc.threshold;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

@SuppressWarnings({"javadoc"})
public class ChainCodeTest {
  @Test
  public void canConstructWithNoRun() {
    int x = 56;
    int y = 72;
    ChainCode code = new ChainCode(x, y);
    Assertions.assertEquals(x, code.getX(), "X origin");
    Assertions.assertEquals(y, code.getY(), "Y origin");
    Assertions.assertEquals(0, code.getLength(), "Length");
    Assertions.assertEquals(0, code.getLength(), "Length");
    Assertions.assertEquals(1, code.getSize(), "Size");
    Assertions.assertArrayEquals(new int[] {x, y}, code.getEnd(), "End");
    Assertions.assertArrayEquals(new int[0], code.getRun(), "Run");
    String text = code.toString();
    Assertions.assertTrue(text.contains(String.valueOf(x)));
    Assertions.assertTrue(text.contains(String.valueOf(y)));
    Assertions.assertEquals(text, code.toString());

    // Check reverse
    ChainCode reversed = code.reverse();

    Assertions.assertEquals(x, reversed.getX(), "Reverse X origin");
    Assertions.assertEquals(y, reversed.getY(), "Reverse Y origin");
    Assertions.assertEquals(0, reversed.getLength(), 1e-3, "Reverse Length");
    Assertions.assertEquals(1, reversed.getSize(), "Reverse Size");
    Assertions.assertArrayEquals(new int[] {x, y}, reversed.getEnd(), "Reverse End");

    Assertions.assertArrayEquals(new int[0], reversed.getRun(), "Reverse Run");
  }

  @Test
  public void addDirectionThrowsWithBdDirection() {
    int x = 56;
    int y = 72;
    ChainCode code = new ChainCode(x, y);
    Assertions.assertThrows(IllegalArgumentException.class, () -> code.add(-1),
        "Negative direction");
    Assertions.assertThrows(IllegalArgumentException.class, () -> code.add(8), "Direction above 7");
  }

  @Test
  public void canTraverseCircle() {
    int x = 56;
    int y = 72;
    ChainCode code = new ChainCode(x, y);

    for (int i = 0; i < 8; i++) {
      code.add(i);
    }

    Assertions.assertEquals(x, code.getX(), "X origin");
    Assertions.assertEquals(y, code.getY(), "Y origin");
    double length = 4 + 4 * Math.sqrt(2);
    Assertions.assertEquals(length, code.getLength(), 1e-3, "Length");
    Assertions.assertEquals(length, code.getLength(), 1e-3, "Length");
    Assertions.assertEquals(9, code.getSize(), "Size");
    Assertions.assertArrayEquals(new int[] {x, y}, code.getEnd(), "End");

    Assertions.assertArrayEquals(new int[] {0, 1, 2, 3, 4, 5, 6, 7}, code.getRun(), "Run");

    // Check reverse
    ChainCode reversed = code.reverse();

    Assertions.assertEquals(x, reversed.getX(), "Reverse X origin");
    Assertions.assertEquals(y, reversed.getY(), "Reverse Y origin");
    Assertions.assertEquals(length, reversed.getLength(), 1e-3, "Reverse Length");
    Assertions.assertEquals(9, reversed.getSize(), "Reverse Size");
    Assertions.assertArrayEquals(new int[] {x, y}, reversed.getEnd(), "Reverse End");

    // opposite direction is adjusted by 4
    Assertions.assertArrayEquals(new int[] {3, 2, 1, 0, 7, 6, 5, 4}, reversed.getRun(),
        "Reverse Run");
  }

  @Test
  public void canAddSingleDirection() {
    for (int i = 0; i < 8; i++) {
      int x = 56;
      int y = 72;
      ChainCode code = new ChainCode(x, y);
      code.add(i);
      double length = ChainCode.getDirectionLength(i);
      Assertions.assertEquals(length, code.getLength(), 1e-3, "Length");
      Assertions.assertEquals(2, code.getSize(), "Size");
      int endx = x + ChainCode.getXDirection(i);
      int endy = y + ChainCode.getYDirection(i);
      Assertions.assertArrayEquals(new int[] {endx, endy}, code.getEnd(), "End");

      Assertions.assertArrayEquals(new int[] {i}, code.getRun(), "Run");

      String text = code.toString();
      String directionText = ":" + ChainCode.getXDirection(i) + "," + ChainCode.getYDirection(i);
      Assertions.assertTrue(text.contains(directionText));

      // Check reverse
      ChainCode reversed = code.reverse();

      Assertions.assertEquals(endx, reversed.getX(), "Reverse X origin");
      Assertions.assertEquals(endy, reversed.getY(), "Reverse Y origin");
      Assertions.assertEquals(length, reversed.getLength(), 1e-3, "Reverse Length");
      Assertions.assertEquals(2, reversed.getSize(), "Reverse Size");
      Assertions.assertArrayEquals(new int[] {x, y}, reversed.getEnd(), "Reverse End");

      // opposite direction is adjusted by 4
      Assertions.assertArrayEquals(new int[] {(i + 4) % 8}, reversed.getRun(), "Reverse Run");
    }
  }

  @Test
  public void canCompare() {
    ChainCode[] codes = new ChainCode[3];
    codes[0] = new ChainCode(0, 0);
    codes[0].add(0);
    codes[1] = new ChainCode(1, 2);
    codes[2] = new ChainCode(3, 4);

    for (int i = 0; i < 3; i++) {
      Assertions.assertEquals(0, ChainCode.compare(codes[i], codes[i]), "Self compare");
      for (int j = i + 1; j < 3; j++) {
        int result1 = ChainCode.compare(codes[i], codes[j]);
        int result2 = ChainCode.compare(codes[j], codes[i]);
        Assertions.assertFalse(0 == result1, "Should be different");
        Assertions.assertEquals(result1, -result2, "Swapped arguments should be opposite");
      }
    }
  }
}
