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
package com.examples.with.different.packagename.continuous;

public class MoreBranches {
	public int foo(int x){
		
		if(x==0){
			return 0;
		}
		
		if(x==1){
			return 1;
		}
		
		if(x==2){
			return 2;
		}
		
		if(x==4){
			return 4;
		}
		
		if(x==5){
			return 5;
		}
		
		return 3;
	}

}
