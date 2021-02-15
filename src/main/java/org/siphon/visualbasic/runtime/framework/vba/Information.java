/*******************************************************************************
 * Copyright (C) 2017 Inshua<inshua@gmail.com>
 * 
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 * 
 * The above copyright notice and this permission notice shall be included in
 * all copies or substantial portions of the Software.
 * 
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 * SOFTWARE.
 ******************************************************************************/
package org.siphon.visualbasic.runtime.framework.vba;

import org.siphon.visualbasic.Interpreter;
import org.siphon.visualbasic.runtime.CallFrame;
import org.siphon.visualbasic.runtime.ErrObject;
import org.siphon.visualbasic.runtime.VbValue;
import org.siphon.visualbasic.runtime.VbVarType;
import org.siphon.visualbasic.runtime.framework.Enums.VbIMEStatus;
import org.siphon.visualbasic.runtime.framework.VbMethod;

public class Information {

	@VbMethod(value = "Function Err() As ErrObject", withIntepreter = true)
	public static ErrObject Err(Interpreter interpreter, CallFrame frame) {
		return frame.error;
	}

	@VbMethod("Function IMEStatus() As VbIMEStatus")
	public static VbIMEStatus IMEStatus() {
		throw new UnsupportedOperationException("TODO");
	}

	@VbMethod("Function IsArray(VarName) As Boolean")
	public static boolean isArray(VbValue varName) {
		if (varName.isVariant()) {
			return ((VbValue) varName.value).varType.vbType == VbVarType.vbArray;
		} else {
			return varName.varType.vbType == VbVarType.vbArray;
		}
	}

	@VbMethod("Function IsDate(Expression) As Boolean")
	public static boolean isDate(VbValue expression) {
		if (expression.isVariant())
			return isDate((VbValue) expression.value);
		if (expression.varType.vbType == VbVarType.vbDate) {
			return true;
		}
		if (expression.varType.vbType == VbVarType.vbString) {
			try {
				VbValue.parseDate((String) expression.value);
				return true;
			} catch (Exception e) {
				return false;
			}
		}

		return false;
	}
	
	@VbMethod("Function IsEmpty(Expression) As Boolean")
	public static boolean isEmpty(VbValue expression) {
		return expression.isEmpty(); 
	}
	
	@VbMethod("Function IsError(Expression) As Boolean")
	public static boolean isError(VbValue expression) {
		return expression.isError();
	}
	
	@VbMethod("Function IsMissing(Expression) As Boolean")
	public static boolean isMissing(VbValue expression) {
		return expression.isMissing();
	}

	@VbMethod("Function IsNull(Expression) As Boolean")
	public static boolean isNull(VbValue expression) {
		return expression.isNull();
	}
	
	@VbMethod("Function IsNumeric(Expression) As Boolean")
	public static boolean isNumeric(VbValue expression) {
		Object v = expression.getExactValue();
		if(v instanceof Number){
			return true;
		} else if(v instanceof String){
			try{
				VbValue.CDbl(expression);
				return true;
			}catch(Exception e){
				return false;
			}
		}
		return false;
	}
	
	@VbMethod("Function IsObject(Expression) As Boolean")
	public static boolean isObject(VbValue expression) {
		return expression.isObject();
	}
	
	@VbMethod("Function RGB(Red As Integer, Green As Integer, Blue As Integer) As Long")
	public static int rgb(int red, int green, int blue){
		int rgb = red;
		rgb = (rgb << 8) + green;
		rgb = (rgb << 8) + blue;
		return rgb;
	}
	
	@VbMethod("Function TypeName(VarName) As String")
	public static String typeName(VbValue varName){
		if(varName.isVariant()){
			return typeName((VbValue)varName.value);
		}
		return varName.varType.getTypeName();
	}
	
	@VbMethod("Function VarType(VarName) As VbVarType")
	public static int varType(VbValue varName){
		return varName.varType.vbType;
	}
	
}
