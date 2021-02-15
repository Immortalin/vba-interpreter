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
package org.siphon.visualbasic.compile;

import org.antlr.v4.runtime.CommonTokenStream;
import org.antlr.v4.runtime.tree.ParseTree;
import org.apache.commons.lang3.StringUtils;
import org.siphon.visualbasic.*;
import org.siphon.visualbasic.runtime.CallFrame;
import org.siphon.visualbasic.runtime.VbRuntimeException;
import org.siphon.visualbasic.runtime.VbValue;
import org.siphon.visualbasic.runtime.framework.VbEvent;
import org.siphon.visualbasic.runtime.framework.VbMethod;
import vba.VbaLexer;
import vba.VbaParser;
import vba.VbaParser.*;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;

/**
 * Java 模块。只取导出的成员。
 * 类模块应使用 JavaClassModule
 */
public class JavaClassModuleDecl extends ClassModuleDecl {

	public static final ClassModuleDecl JAVA_OBJECT = new JavaClassModuleDecl();

	private Class javaClass;
	
	public Class getJavaClass() {
		return this.javaClass;
	}

	public JavaClassModuleDecl(Library lib, Compiler compiler, Class javaClass) {
		super(lib, compiler);
		
		this.name = javaClass.getSimpleName();
		
		this.javaClass = javaClass;
		
		int classModifiers = this.javaClass.getModifiers();
		if(Modifier.isPublic(classModifiers)){
			this.visibility = Visibility.PUBLIC;
		} else if(!Modifier.isPrivate(classModifiers)){
			this.visibility = Visibility.FRIEND;
		}
		
		for(Method method : this.javaClass.getMethods()){
			processJavaMethod(lib, compiler, method);
		}
		
		for(Field fld: this.javaClass.getFields()){
			int modifier = fld.getModifiers();
			if(Modifier.isStatic(modifier) && Modifier.isPublic(modifier) && Modifier.isFinal(modifier)){
				try {
					VbValue value = VbValue.fromJava(fld.get(null));
					ConstDecl constDecl = new ConstDecl(lib, this, value);
					constDecl.varType = value.varType;
					this.addMember(constDecl);
				} catch (IllegalArgumentException e) {
				} catch (IllegalAccessException e) {
				}
			} else {
				VbEvent[] vbEvents = fld.getAnnotationsByType(VbEvent.class);
				if(vbEvents.length > 0){
					VbEvent vbEvent = vbEvents[0]; 
					String decl = vbEvent.value();
					
					VbaLexer lexer = new VbaLexer(new org.antlr.v4.runtime.ANTLRInputStream(decl));
					CommonTokenStream tokenStream = new CommonTokenStream(lexer);
					VbaParser parser = new VbaParser(tokenStream);					
					ParseTree element = parser.eventStmt();
					if(element instanceof EventStmtContext){
						EventDecl eventDecl = compiler.compileEventDecl((EventStmtContext) element, this);
						if(eventDecl != null) {
							JavaEventDecl javaEventDecl = new JavaEventDecl(lib, this, fld);
							javaEventDecl.arguments = eventDecl.arguments;
							javaEventDecl.name = eventDecl.name;
							javaEventDecl.visibility = eventDecl.visibility;
							javaEventDecl.setWithIntepreter(true);
							
							this.addEvent((EventStmtContext) element, javaEventDecl);
						}
					} else {
						throw new UnsupportedOperationException("cannot be " + element);
					}
				}
			}
		}
	}

	private void processJavaMethod(Library lib, Compiler compiler, Method method) {
		int modifier = method.getModifiers();
		if(Modifier.isStatic(modifier) == false && Modifier.isPublic(modifier) && method.getDeclaringClass() != Object.class){
			VbMethod[] vbMethods = method.getAnnotationsByType(VbMethod.class);
			if(vbMethods.length > 0){
				VbMethod vbMethod = vbMethods[0];
				
				if(StringUtils.isEmpty(vbMethod.value())){
					String name = method.getName();
					if((name.startsWith("get") || name.startsWith("set")) && name.length() > 3 && Character.isLowerCase(name.charAt(3)) == false){
						// System.out.println(method);
						JavaMethod m = new JavaMethod(lib, this, method, true, vbMethod.withIntepreter());
						this.addMember(m);
						name = m.name;
					} else{
						this.addMember(new JavaMethod(lib, this, method, vbMethod.withIntepreter()));
					}
					if(vbMethod.isDefault()){
						this.setDefaultMember(name);
					}
					if(vbMethod.isDictionary()){
						this.setDictionaryMember(name);
					}
					if(vbMethod.isIterator()){
						this.setIteratorMember(name);
					}
				} else {
					String decl = vbMethod.value();
					String methodType = decl.substring(0, decl.indexOf(' ')); 
					decl = decl + "\r\n" + "End " + methodType;
					VbaLexer lexer = new VbaLexer(new org.antlr.v4.runtime.ANTLRInputStream(decl));
					CommonTokenStream tokenStream = new CommonTokenStream(lexer);
					VbaParser parser = new VbaParser(tokenStream);
					ParseTree element = parser.moduleBodyElement().getChild(0);
					MethodDecl methodDecl = null;
					JavaMethod m = null;
					if(element instanceof FunctionStmtContext){
						methodDecl = compiler.compileMethodBaseInfo((FunctionStmtContext) element, this);
						m = new JavaMethod(lib, this, methodDecl, method, vbMethod.withIntepreter());
						this.addMember(m);
					} else if(element instanceof SubStmtContext){
						methodDecl = compiler.compileMethodBaseInfo((SubStmtContext) element, this);
						m = new JavaMethod(lib, this, methodDecl, method, vbMethod.withIntepreter());
						this.addMember(m);
					} else if (element instanceof PropertyGetStmtContext) {
						methodDecl = compiler.compilePropertyGetBaseInfo((PropertyGetStmtContext) element, this);
						m = new JavaMethod(lib, this, methodDecl, method, vbMethod.withIntepreter());
						this.addMember(m);
					} else if (element instanceof PropertyLetStmtContext) {
						methodDecl = compiler.compilePropertyLetBaseInfo((PropertyLetStmtContext) element, this);
						m = new JavaMethod(lib, this, methodDecl, method, vbMethod.withIntepreter());
						this.addMember(m);
					} else if (element instanceof PropertySetStmtContext) {
						methodDecl = compiler.compilePropertySetBaseInfo((PropertySetStmtContext) element, this);
						m = new JavaMethod(lib, this, methodDecl, method, vbMethod.withIntepreter());
						this.addMember(m);
					} else {
						throw new UnsupportedOperationException("cannot be " + element);
					}
					if(vbMethod.isDefault()){
						this.setDefaultMember(m.upperCaseName());
					}
					if(vbMethod.isDictionary()){
						this.setDictionaryMember(m.upperCaseName());
					}
					if(vbMethod.isIterator()){
						this.setIteratorMember(m.upperCaseName());
					}
				}
			}
		}
	}

	private JavaClassModuleDecl() {
		super();
	}

	public Object newInstance(Interpreter interpreter, CallFrame frame, SourceLocation sourceLocation)
			throws VbRuntimeException {
		try {
			return this.javaClass.newInstance();
		} catch (InstantiationException | IllegalAccessException e) {
			throw new VbRuntimeException(VbRuntimeException.ActiveX部件不能建立对象或返回对此对象的引用, sourceLocation, e);
		}
	}

}
