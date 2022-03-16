/*
 * Copyright (C) 2010-2018 Gordon Fraser, Andrea Arcuri and SmartUt
 * contributors
 *
 * This file is part of SmartUt.
 *
 * SmartUt is free software: you can redistribute it and/or modify it
 * under the terms of the GNU Lesser General Public License as published
 * by the Free Software Foundation, either version 3.0 of the License, or
 * (at your option) any later version.
 *
 * SmartUt is distributed in the hope that it will be useful, but
 * WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU
 * Lesser Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public
 * License along with SmartUt. If not, see <http://www.gnu.org/licenses/>.
 */
package com.examples.with.different.packagename.testcarver;

import org.junit.Assert;
import org.junit.Test;

public class SimpleTest {

	@Test
	public void actuallTest() {
		Simple sim = new Simple();

		boolean b0 = sim.incr();
		Assert.assertFalse(b0);

		boolean b1 = sim.sameValues(2, 4);
		Assert.assertFalse(b1);

		boolean b2 = sim.sameValues(5, 5);
		Assert.assertTrue(b2);
	}

	@SuppressWarnings("unused")
	public void thisIsNotATest() {
		Simple sim = new Simple();
	}
}
