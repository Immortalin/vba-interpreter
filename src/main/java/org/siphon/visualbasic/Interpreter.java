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
package org.siphon.visualbasic;

import org.apache.commons.lang3.StringUtils;
import org.siphon.visualbasic.Project.ProjectType;
import org.siphon.visualbasic.compile.Compiler;
import org.siphon.visualbasic.compile.*;
import org.siphon.visualbasic.runtime.*;
import org.siphon.visualbasic.runtime.framework.Debug;
import org.siphon.visualbasic.runtime.framework.vb.Control;
import org.siphon.visualbasic.runtime.framework.vb.Form;
import org.siphon.visualbasic.runtime.statements.NamedArgumentStatement;
import org.siphon.visualbasic.runtime.statements.ShowFormStatement;

import java.io.File;
import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.util.*;

//import com.sun.corba.se.impl.naming.pcosnaming.NameServer;

public class Interpreter {

	private Map<String, RuntimeLibrary> runtimeLibs = new HashMap<>();

	Stack<CallFrame> callFrames = new Stack<>();

	private Debugger debugger = new Debugger(this);

	private DebuggerAction debuggerAction = DebuggerAction.NONE;

	private CallFrame debuggerFrame;

	public List<CallFrame> getCallFrames() {
		return callFrames.subList(0, callFrames.size());
	}

	public Debugger getDebugger() {
		return debugger;
	}

	public void setDebugger(Debugger debugger) {
		this.debugger = debugger;
	}

	public Interpreter load(List<Statement> statements) throws VbRuntimeException {
		CallFrame callFrame = new CallFrame(runtimeLibs, null, null);
		for (Statement statement : statements) {
			statement.eval(this, callFrame);
		}
		return this;
	}

	public Object invoke(String library, String module, String methodName, Object... arguments)
			throws ArgumentException, VbRuntimeException {
		library = library.toUpperCase();
		module = module.toUpperCase();
		methodName = methodName.toUpperCase();

		ModuleInstance runtimeModule = ((RuntimeLibrary) runtimeLibs.get(library)).modules.get(module);
		MethodDecl method = (MethodDecl) runtimeModule.getModuleDecl().members.get(methodName);

		return callMethod(runtimeModule, method, arguments);
	}

	public VbValue callMethod(ModuleInstance runtimeModule, List<Statement> argumentStatements, MethodDecl method,
			Object... arguments) throws VbRuntimeException {
		try {
			return this.callMethod(runtimeModule, method, arguments);
		} catch (ArgumentException e) {
			if(argumentStatements == null || e.getArgumetIndex() >= argumentStatements.size()){
				throw new VbRuntimeException(VbRuntimeException.参数的个数错误或无效的属性设置);
			}
			Statement arg = argumentStatements.get(e.getArgumetIndex());			
			if (e.getCause() instanceof OverflowException) {
				throw new VbRuntimeException(VbRuntimeException.溢出, arg.getSourceLocation());
			} else if (e.getCause() instanceof ClassCastException) {
				throw new VbRuntimeException(VbRuntimeException.类型不匹配, arg.getSourceLocation());
			} else if (e.getCause() instanceof NullValueException) {
				throw new VbRuntimeException(VbRuntimeException.Null的使用无效, arg.getSourceLocation());
			} else {
				throw new ImpossibleException();
			}
		}
	}

	/**
	 * 调用函数
	 * 
	 * @param runtimeModule
	 * @param method
	 * @param arguments
	 * @return
	 * @throws ArgumentException
	 * @throws VbRuntimeException
	 */
	public synchronized VbValue callMethod(ModuleInstance runtimeModule, MethodDecl method, Object... arguments)
			throws ArgumentException, VbRuntimeException {
		assert runtimeModule != null;
		if (method instanceof JavaMethod) {
			JavaMethod javaMethod = (JavaMethod) method;
			try {
				if (runtimeModule instanceof JavaModuleInstance) {
					Object obj = ((JavaModuleInstance) runtimeModule).getInstance();
					Object result = javaMethod.javaMethod.invoke(obj,
							toJavaArguments(arguments, javaMethod, javaMethod.javaMethod.getParameterTypes(), this.getCurrentFrame()));
					return VbValue.fromJava(result, method.returnType);
				} else if(runtimeModule.getModuleDecl() instanceof FormModuleDecl) {
					VbDecl decl = (VbDecl) runtimeModule.getMember("FORM");
					VbVariable var = runtimeModule.variables.get(decl);
					JavaModuleInstance inst = (JavaModuleInstance) var.value.value;
					Object result = javaMethod.javaMethod.invoke(inst.getInstance(),
							toJavaArguments(arguments, javaMethod, javaMethod.javaMethod.getParameterTypes(), this.getCurrentFrame()));
					return VbValue.fromJava(result, method.returnType);
				} else {
					Object result = javaMethod.javaMethod.invoke(null,
							toJavaArguments(arguments, javaMethod, javaMethod.javaMethod.getParameterTypes(), this.getCurrentFrame()));
					return VbValue.fromJava(result, method.returnType);
				}
			} catch (IllegalAccessException | IllegalArgumentException e) {
				e.printStackTrace();
				throw new VbRuntimeException(VbRuntimeException.无效的过程调用, e);
			} catch (InvocationTargetException e) {
				if (e.getTargetException() instanceof VbRuntimeException) {
					throw (VbRuntimeException) e.getTargetException();
				} else {
					e.printStackTrace();
					throw new VbRuntimeException(VbRuntimeException.无效的过程调用, e);
				}
			}
		} else {
			CallFrame frame = new CallFrame(runtimeLibs, runtimeModule, method);
			Map<VarDecl, VbVariable> args = bindArguments(method, arguments);
			if (method.methodType == MethodType.Function || method.methodType == MethodType.PropertyGet
					|| method.methodType == MethodType.Rule) {
				frame.local.put(method.result, method.result.createVar());
			}
			frame.local.putAll(args);
			return eval(frame);
		}
	}

	private Object[] toJavaArguments(Object[] arguments, JavaMethod method, Class<?>[] paramTypes, CallFrame callFrame) throws ArgumentException { // TODO 检查能否转换
		Map<VarDecl, VbVariable> args = bindArguments(method, arguments);
		
		Object[] result = new Object[paramTypes.length];
		int offset = 0;
		if(method.isWithInterpreter()){
			result[0] = this;
			result[1] = callFrame;
			offset = 2;
		}
		for (int i = 0; i < method.arguments.size(); i++) {
			Class<?> paramType = paramTypes[i];
			ArgumentDecl argDecl = method.arguments.get(i);
			VbValue argCall = args.get(argDecl).value;

			if (paramType == VbValue.class) {
				result[i + offset] = argCall;
			} else {
				result[i + offset] = VbValue.vbValueToJava(argCall);
			}
		}		
		return result;
	}

	private VbValue eval(CallFrame frame) throws VbRuntimeException {
		callFrames.push(frame);

		MethodDecl method = frame.method;
		if (method.methodType == MethodType.Rule) {
			RuleDecl rule = (RuleDecl) method;
			try {
				method = rule.findMatchMethod(this, frame);
			} catch (VbRuntimeException e) {
				SourceLocation s = e.getSourceLocation();
				if (s == SourceLocation.ByInterpreter || s == null) {
					s = rule.getLastTestEntrance().getSourceLocation();
				}
				if (e.hasVbStackTrace() == false)
					e.setVbStackTrace(toStackTrace(callFrames));
				frame.error.wrap(e, frame, s);
				frame.error.setHandled(false);
			}
			if (method == null && frame.error.hasError() == false) {
				callFrames.pop();
				throw new VbRuntimeException(VbRuntimeException.无匹配规则); // TODO 错误处理较为复杂
			}
			frame.method = method;
		}

		if (frame.error.hasError() == false) {
			while (frame.nextStatement < method.statements.size()) {
				frame.statementIndex = frame.nextStatement;
				Statement statement = method.statements.get(frame.statementIndex);
				frame.nextStatement++;
				switch (debuggerAction) {
				case STEP_INTO:
					debuggerAction = DebuggerAction.NONE;
					if (debugger != null)
						debugger.stop();
					break;
				case STEP_OVER:
					if (frame == debuggerFrame) {
						debuggerAction = DebuggerAction.NONE;
						if (debugger != null)
							debugger.stop();
					}
					break;
				default:
					break;
				}

				try {
					try { 
						statement.eval(this, frame);
					} catch(VbRuntimeException e) {
						throw e;
					} catch(Exception e) {
						throw new VbRuntimeException(VbRuntimeException.对象不支持此属性或方法, e);
					}
				} catch (VbRuntimeException e) {
					SourceLocation s = e.getSourceLocation();
					if (s == SourceLocation.ByInterpreter || s == null) {
						s = statement.getSourceLocation();
					}
					if (e.hasVbStackTrace() == false)
						e.setVbStackTrace(toStackTrace(callFrames));
					frame.error.wrap(e, frame, s);
					frame.error.setHandled(false);
				} 

				if (frame.error.hasError()) {
					if (frame.errorHandler != null) {
						frame.error.setHandled(true);
						try {
							frame.errorHandler.eval(this, frame);
						} catch (VbRuntimeException e1) {
							// 错误处理程序仅仅跳转运行语句，不会出错
						}
					} else {
						break; // break loop
					}
				}
			}
		}

		callFrames.pop();

		if (frame.error.hasError()) {
			throw frame.error.getException();
		}

		switch (debuggerAction) {
		case STEP_RETURN:
			debuggerAction = DebuggerAction.NONE;
			if (debugger != null)
				debugger.stop();
			break;
		default:
			break;
		}

		if (method.methodType == MethodType.Function || method.methodType == MethodType.PropertyGet) {
			return frame.getFunctionResult();
		} else {
			return null;
		}
	}

	private StackTraceElement[] toStackTrace(Stack<CallFrame> callFrames) {
		List<StackTraceElement> result = new ArrayList<>();
		for (int i = callFrames.size() - 1; i >= 0; i--) {
			CallFrame frame = callFrames.get(i);
			Statement statement = frame.getCurrentStatement();
			StackTraceElement e = new StackTraceElement(frame.module.getModuleDecl().name, 
					StringUtils.defaultIfEmpty(frame.method.name, "<UNKOWN METHOD>"),
					frame.module.getModuleDecl().getSrcFile().getName(), statement.getSourceLocation().getLine());
			result.add(e);
		}
		return (StackTraceElement[]) result.toArray(new StackTraceElement[result.size()]);
	}

	private Map<VarDecl, VbVariable> bindArguments(MethodDecl methodDef, Object[] arguments) throws ArgumentException {
		Map<VarDecl, VbVariable> result = new HashMap<>();

		int i = 0;
		for (ArgumentDecl argDef : methodDef.arguments) {
			Object arg = null;
			if (i < arguments.length) {
				arg = arguments[i++];
			}
			VbValue value = null;
			if (argDef.isParamArray) {
				// TODO if arg == null, throw not optional
				List<VbValue> ls = new ArrayList<>();
				if (arg != null) {
					try {
						ls.add(VbValue.cast(VbValue.fromJava(arg), VbVarType.vbVariant)); // ParamArray 只能是 Variant 数组
					} catch (OverflowException e1) {
					}
					for (; i < arguments.length; i++) {
						try {
							ls.add(VbValue.cast(VbValue.fromJava(arguments[i]), VbVarType.vbVariant)); // ParamArray 只能是 Variant 数组
						} catch (OverflowException e) {
						}
					}
					ArrayDef paramArrayDef = new ArrayDef(VbVarType.VbVariant,
							new ArrayDef.Rank[] { new ArrayDef.Rank(0, ls.size() - 1) });
					VbVarType arrType = new VbVarType(VbVarType.vbArray, null, paramArrayDef, null);
					VbArray arr = new VbArray(arrType);
					for (int j = 0; j < ls.size(); j++) {
						try {
							arr.set(new Integer[] { j }, ls.get(j), null);
						} catch (VbRuntimeException e) {
						}
					}
					value = arr;
				} else {
					value = VbValue.Missing.clone();
				}
			} else {
				if (arg == null) {
					if (argDef.optional) {
						value = argDef.defaultValue.clone();
					} else {
						throw new ArgumentException(i, new VbRuntimeException(VbRuntimeException.参数的个数错误或无效的属性设置));
					}
				} else {
					if (arg instanceof VbValue) {
						value = (VbValue) arg;
						if (Compiler.isArgTypeMatch(argDef.varType, value.varType, true)) {
							if (argDef.mode == ArgumentMode.ByRef) {
								// value = arg
							} else {
								value = value.clone();
							}
						} else {
							try {
								value = VbValue.cast((VbValue) arg, argDef.varType.vbType);
							} catch (OverflowException | ClassCastException | DivByZeroException e) {
								throw new ArgumentException(i, e);
							}
						}
					} else {
						try {
							value = VbValue.fromJava(argDef.varType.vbType, arg);
						} catch (OverflowException | ClassCastException | DivByZeroException e) {
							throw new ArgumentException(i, e);
						}
					}
				}
			}
			VbVariable varibale = new VbVariable(argDef, value);
			result.put(argDef, varibale);
		}

		return result;
	}

	public Library loadVbProject(String vbpPath, String charset)
			throws IOException, UnspportedActiveXReferenceException, VbErrorsException, VbRuntimeException {
		Project project = new Project(vbpPath, charset);
		return loadVbProject(project);
	}

	Library loadVbProject(Project project) throws VbErrorsException, VbRuntimeException {
		Compiler compiler = new Compiler();
		compiler.bindObject("DEBUG", VbValue.fromJava(new Debug()));

		// compiler.setLibraries(new Library[] { vba });

		List<String> files = new ArrayList<>();
		for (File file : project.getModuleFiles()) {
			files.add(file.getAbsolutePath());
		}
		Library lib = compiler.compile(project.getName(), (String[]) files.toArray(new String[files.size()]), project.getCharset());

		System.out.println("compiled code");
		System.out.println(lib);
		System.out.println("----- above ----");

		this.load(compiler.generateStatements());

		return lib;
	}

	public void executeVbProject(String vbpPath, String charset) throws IOException, UnspportedActiveXReferenceException, VbErrorsException,
			NotFoundException, VbRuntimeException, ArgumentException {
		Project project = new Project(vbpPath, charset);
		Library lib = this.loadVbProject(vbpPath, charset);
		if (project.getType() == ProjectType.Exe) {
			String s = project.getStartup();
			if ("Sub Main".equalsIgnoreCase(s)) {
				MethodDecl subMain = lib.findSubMain();
				if (subMain == null)
					throw new NotFoundException("Sub Main not found");
				this.callMethod(subMain);
			} else {	// 启动对象为窗体
				FormModuleDecl formDecl = (FormModuleDecl) lib.modules.get(s.toUpperCase());
				RuntimeLibrary runtimeLib = this.runtimeLibs.get(project.getName().toUpperCase());
				VbVariable form = runtimeLib.variables.get(formDecl.getVarDecl());
				MethodDecl subMain = new MethodDecl(lib, formDecl, MethodType.Sub);
				subMain.module = formDecl;
				subMain.statements.add(new ShowFormStatement(form));
				
				ModuleInstance instance = (ModuleInstance) form.value.value;
				this.callMethod(instance, subMain);
			}
		}
	}

	private void callMethod(MethodDecl methodDecl) throws VbRuntimeException {
		RuntimeLibrary lib = (RuntimeLibrary) this.runtimeLibs.get(methodDecl.library.upperCaseName());
		this.callMethod(lib.modules.get(methodDecl.module.upperCaseName()), (List<Statement>) null, methodDecl);
	}

	public void loadVbProjectGroup(String vbgPath) {

	}

	public void processNamedArguments(List<ArgumentDecl> argDecls, List<Statement> argCalls) throws VbRuntimeException {

		boolean namedArguments = false;
		int nameArgumentStart = -1;
		NamedArgumentStatement firstNamedArg = null;
		for (int i = 0; i < argCalls.size(); i++) {
			Statement arg = argCalls.get(i);
			if (namedArguments == false) {
				if (arg instanceof NamedArgumentStatement) {
					namedArguments = true;
					nameArgumentStart = i;
					firstNamedArg = (NamedArgumentStatement) arg;
				}
			} else {
				if (arg instanceof NamedArgumentStatement == false) {
					// When you supply arguments by a mixture of position and name, the positional arguments must all come first.
					// Once you supply an argument by name, the remaining arguments must all be by name.
					throw new VbRuntimeException(VbRuntimeException.找不到指定参数, firstNamedArg.getSourceLocation()); // 未找到命名参数
				}
			}

		}

		if (namedArguments) { // sort named arguments
			for (int i = argCalls.size(); i < argDecls.size(); i++) {
				argCalls.add(null);
			}

			for (int i = nameArgumentStart; i < argCalls.size();) {
				Statement stmt = argCalls.get(i);
				if (stmt instanceof NamedArgumentStatement == false) {
					i++;
					continue;
				}
				NamedArgumentStatement arg = (NamedArgumentStatement) stmt;
				String name = arg.getName();
				int newIndex = Compiler.findNamedArgIndex(argDecls, name.toUpperCase());
				if (newIndex == -1) {
					throw new VbRuntimeException(VbRuntimeException.找不到指定参数, arg.getSourceLocation()); // 未找到命名参数
				} else if (newIndex < nameArgumentStart) {
					throw new VbRuntimeException(VbRuntimeException.找不到指定参数, arg.getSourceLocation()); // 未找到命名参数
				} else if (newIndex == i) {
					i++;
				} else {
					// swap
					Statement prev = argCalls.get(newIndex);
					argCalls.set(newIndex, arg);
					argCalls.set(i, prev);
				}
			}

			for (int i = 0; i < argCalls.size(); i++) {
				Statement arg = argCalls.get(i);
				if (arg instanceof NamedArgumentStatement) {
					argCalls.set(i, ((NamedArgumentStatement) arg).getStatement());
				}
			}
		}
	}

	// 调用0参数的默认函数或GET属性，如不具备默认属性或默认函数，返回 value 本身
	public VbValue evalDefaultMember(VbValue value, CallFrame frame, SourceLocation sourceLocation) throws VbRuntimeException {
		ClassModuleDecl classModule = value.varType.getClassModuleDecl();
		if (classModule == null)
			return value;
		ModuleMemberDecl defaultMember = classModule.getDefaultMember();
		if (defaultMember != null) {
			if (defaultMember instanceof MethodDecl) {
				MethodDecl m = (MethodDecl) defaultMember;
				if (m.arguments.size() == 0 && m.returnType != null) {
					return this.callMethod((ModuleInstance) value.value, (List<Statement>) null, m);
				}
			} else if (defaultMember instanceof PropertyDecl) {
				PropertyDecl p = (PropertyDecl) defaultMember;
				if (p.get != null && p.getArguments().size() == 0) {
					return this.callMethod((ModuleInstance) value.value, (List<Statement>) null, p.get);
				}
			}
		}
		return value;
	}

	public void setDebuggerAction(DebuggerAction debuggerAction) {
		this.debuggerAction = debuggerAction;
		if (debuggerAction == DebuggerAction.STEP_RETURN) {
			if (callFrames.size() > 1) {
				CallFrame frame = callFrames.get(callFrames.size() - 2);
				this.debuggerFrame = frame;
			} else {
				this.debuggerFrame = null;
			}
		} else {
			this.debuggerFrame = callFrames.peek();
		}
	}

	public CallFrame getCurrentFrame() {
		return callFrames.peek();
	}

	public void initControl(ModuleInstance thisForm, JavaModuleInstance baseForm, Control container, ControlDef controlDef) throws VbRuntimeException, ArgumentException {

		ModuleDecl formDecl = thisForm.getModuleDecl();
		Form form = (Form) baseForm.getInstance();
		
		for(ControlDef child : controlDef.getChildren()) {
			VarDecl controlDecl = (VarDecl) formDecl.members.get(child.getName().toUpperCase());
			VbVariable var = thisForm.variables.get(controlDecl);
			Control control = null;
			if(var.varType.isJavaObject() && var.varType.getWrappedJavaClass() == ControlArray.class) {
				// TODO Load 控件数组
				VbVariable controlArrayVar = var;
				controlArrayVar.value.ensureInstanceInited(this, this.getCurrentFrame(), SourceLocation.ByInterpreter);
				ControlArray arr = (ControlArray) controlArrayVar.value.toJava();
				VarDecl elementDecl = new VarDecl(formDecl.library, formDecl);
				elementDecl.varType = ((MethodDecl)var.varType.getDefaultMember()).returnType;
				elementDecl.withEvents = true;
				elementDecl.withNew = true;
				var = elementDecl.createVar();
				var.varDecl.name = controlArrayVar.varDecl.name;
				var.value.ensureInstanceInited(this, this.getCurrentFrame(), null);
				var.assign(var.value, this, this.getCurrentFrame(), null);	// 使触发 bind event handlers
				var.setReadonly(true);
				JavaModuleInstance controlInst = (JavaModuleInstance) var.value.value;
				
				arr.add(var.value);
				
				control = (Control) controlInst.getInstance();
				control.load(form, var.varDecl.name, child, container, this);
			} else {
				var.setReadonly(false);
				var.value.ensureInstanceInited(this, this.getCurrentFrame(), null);
				var.assign(var.value, this, this.getCurrentFrame(), SourceLocation.ByInterpreter);	// 使触发 bind event handlers
				var.setReadonly(true);
				JavaModuleInstance controlInst = (JavaModuleInstance) var.value.value;
				control = (Control) controlInst.getInstance();
				control.load(form, var.varDecl.name, child, container, this);
			}
			// 递归孙子节点
			if(child.getChildren() != null) {
				initControl(thisForm, baseForm, control, child);
			}
		}
	}

	public void ensureFormLoaded(ModuleInstance instance) throws VbRuntimeException, ArgumentException {
		FormModuleDecl formDecl = (FormModuleDecl) instance.getModuleDecl();
		VbVariable var = instance.variables.get(formDecl.members.get("FORM"));
		JavaModuleInstance jmi = (JavaModuleInstance) var.value.value;
		Form form = (Form) jmi.getInstance();
		if(!form.isLoaded()) { 	// 当访问窗体的属性方法时自动加载窗体
			form.setInitForm(new Callback() {
				@Override
				public void run() throws VbRuntimeException, ArgumentException {
					Interpreter.this.initControl((ModuleInstance) instance, jmi, form, formDecl.getControlDef());
				}
			});
			form.load(null, formDecl.name, formDecl.getControlDef(), null, this);
		}
	}

}
