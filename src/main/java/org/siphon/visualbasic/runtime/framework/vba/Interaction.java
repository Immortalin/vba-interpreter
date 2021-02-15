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

import org.siphon.visualbasic.*;
import org.siphon.visualbasic.runtime.*;
import org.siphon.visualbasic.runtime.VbVarType.TypeEnum;
import org.siphon.visualbasic.runtime.framework.Enums.VbCallType;
import org.siphon.visualbasic.runtime.framework.VbMethod;
import org.siphon.visualbasic.runtime.framework.VbParam;
import org.siphon.visualbasic.runtime.statements.EvalAssignableStatement;

import java.lang.reflect.Constructor;
import java.lang.reflect.Parameter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

public class Interaction {

	private static class FindMatchConstructorResult {

		private Constructor<?> constructor;
		
		int similarity;
		
		public FindMatchConstructorResult(Constructor<?> constructor, int similarity) {
			this.constructor = constructor;
			this.similarity = similarity;
		}

	}

	@VbMethod
	public static VbValue CreateObject(
			@VbParam(name="Class", type = TypeEnum.vbString)
			VbValue clazz, 
			@VbParam(name="Arguments", type=TypeEnum.vbVariant, paramArray = true)
			VbValue arguments) throws VbRuntimeException{
		
		String className = (String) clazz.toJava();
		Class<?> cls = null;
		try {
			cls = Class.forName(className);
		} catch (ClassNotFoundException e) {
			throw new VbRuntimeException(VbRuntimeException.ActiveX部件不能建立对象或返回对此对象的引用);
		}
		
		Constructor constructor = null;
		Object obj = null;
		try {
			if(arguments.isMissing()){
				constructor = cls.getConstructor();
				obj = constructor.newInstance();
			} else {
				List<VbValue> lsArgsCall = ((VbArray) arguments).toList();
				constructor = findMatchConstructor(cls.getConstructors(), lsArgsCall);
				if(constructor == null){
					throw new NoSuchMethodException("<Constructor>");
				}
				obj = constructor.newInstance(toJavaArguments(lsArgsCall, constructor.getParameters()));
			}
		} catch (Exception e) {
			throw new VbRuntimeException(VbRuntimeException.ActiveX部件不能建立对象或返回对此对象的引用, e);
		}
		
		
		return VbValue.fromJava(obj, false);
	}

	private static Object[] toJavaArguments(List<VbValue> argCalls, Parameter[] params) {
		List<Object> lsArgs = new ArrayList<>();
		for (int i = 0; i < params.length; i++) {
			Parameter param = params[i];
			if (param.isVarArgs()) {
				Object[] objects = new Object[argCalls.size() - i];
				Class<?> ctype = param.getType().getComponentType();
				for (int j = i; j < argCalls.size(); j++) {
					objects[j - i] = argCalls.get(j).toJava(ctype);
				}
				lsArgs.add(objects);
			} else {
				lsArgs.add(argCalls.get(i).toJava(param.getType()));
			}
		}
		Object[] args = lsArgs.toArray();
		return args;
	}

	private static Constructor findMatchConstructor(Constructor<?>[] constructors, List<VbValue> argCalls) throws NoSuchMethodException {
		int argCallCount = argCalls.size();
		List<FindMatchConstructorResult> maybe = new ArrayList<>();
		for(Constructor<?> constructor : constructors){
			Parameter[] params = constructor.getParameters();
			if (params.length == argCallCount) {
				int s = EvalAssignableStatement.getSimilarity(params, argCalls);
				maybe.add(new FindMatchConstructorResult(constructor, s));
			} else if (params.length < argCallCount && params.length > 0) {
				if (params[params.length - 1].isVarArgs()) {
					int s = EvalAssignableStatement.getSimilarity(params, argCalls);
					maybe.add(new FindMatchConstructorResult(constructor, s));
				}
			}
		}
		if(maybe.size() == 0){
			return null;
		} else if(maybe.size()==1){
			if(maybe.get(0).similarity <= 0) return null;
			
			return maybe.get(0).constructor;
		}

		maybe.sort(new Comparator<FindMatchConstructorResult>() {
			public int compare(FindMatchConstructorResult o1, FindMatchConstructorResult o2) {
				return o2.similarity - o1.similarity;
			}
		});
		if (maybe.get(0).similarity > 0) {
			if (maybe.get(1).similarity > 0  && maybe.get(0).similarity >= maybe.get(1).similarity * 1.2) {	// 匹配的方法区分度够大 
				return maybe.get(0).constructor;
			}
		}

		return null;
	}
	
	@VbMethod(withIntepreter=true, value = "Function CallByName(obj As Object, ProcName As String, CallType As VbCallType, Args() As Variant)")
	public static VbValue CallByName(
			Interpreter interpreter,
			CallFrame callFrame,
			VbValue object, 
			String procName,
			int callType,
			VbValue arguments) throws VbRuntimeException, ArgumentException{
		
		if(object == null || object.isObject() == false) {
			throw new VbRuntimeException(VbRuntimeException.无效的过程调用);
		}
		ModuleInstance instance = (ModuleInstance) object.value;
		VbDecl method = (VbDecl) instance.getMember(procName);
		if(method == null) {
			throw new VbRuntimeException(VbRuntimeException.对象不支持此属性或方法);
		}
		switch(callType) {
		case VbCallType.VbMethod :
			method = null;
			break;
		case VbCallType.VbGet :
			if(method instanceof PropertyDecl == false) {
				throw new VbRuntimeException(VbRuntimeException.对象不支持此属性或方法);
			}
			method = ((PropertyDecl)method).get;
			break;
		case VbCallType.VbLet :
			if(method instanceof PropertyDecl == false) {
				throw new VbRuntimeException(VbRuntimeException.对象不支持此属性或方法);
			}
			method = ((PropertyDecl)method).let;
			break;
		case VbCallType.VbSet :
			if(method instanceof PropertyDecl == false) {
				throw new VbRuntimeException(VbRuntimeException.对象不支持此属性或方法);
			}
			method = ((PropertyDecl)method).set;
			break;
		}
		if(method == null) {
			throw new VbRuntimeException(VbRuntimeException.对象不支持此属性或方法);
		}
		return interpreter.callMethod(instance, (MethodDecl)method, arguments);
	}
}
