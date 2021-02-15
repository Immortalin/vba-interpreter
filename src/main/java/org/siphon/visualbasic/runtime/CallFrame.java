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
package org.siphon.visualbasic.runtime;

import org.apache.commons.collections.MapUtils;
import org.apache.commons.lang3.StringUtils;
import org.siphon.visualbasic.*;
import org.siphon.visualbasic.runtime.statements.GotoStatement;

import java.util.HashMap;
import java.util.Map;
import java.util.Stack;

public class CallFrame {

	public final Map<String, RuntimeLibrary> libs;

	public final Map<VarDecl, VbVariable> local = new HashMap<>();

	public final ModuleInstance module;

	public MethodDecl method;

	public int nextStatement = 0;

	public Stack<Integer> gosubResumeStack = new Stack<>(); // gosub 用的 return 退回点

	public VbValue withObject;

	public GotoStatement errorHandler;

	public final ErrObject error = new ErrObject();

	public GotoStatement prevErrorHandler;

	public int statementIndex = 0;

	public CallFrame(Map<String, RuntimeLibrary> global, ModuleInstance moduleInstance, MethodDecl method) {
		this.libs = global;
		this.module = moduleInstance;
		this.method = method;
	}

	public VbVariable locateVbVariable(VarDecl varDecl, ModuleInstance moduleInstance) {
		if(varDecl instanceof MeDecl){
			return VbVariable.ME;
		}
		
		if (varDecl.methodDecl == null || varDecl.isStatic) {
			if (varDecl.module == this.module.getModuleDecl()) {
				return this.module.variables.get(varDecl);
			} else {
				if (moduleInstance == null) {
					String libName = varDecl.getLibrary().name.toUpperCase();
					RuntimeLibrary lib = (RuntimeLibrary) libs.get(libName);
					if(varDecl.module != null) {
						moduleInstance = lib.modules.get(varDecl.module.name.toUpperCase());
					}
					if(moduleInstance == null) {
						if(lib.variables.containsKey(varDecl)) {
							return lib.variables.get(varDecl);
						}
					}
				}
				return moduleInstance.variables.get(varDecl);
			}
		} else {
			if(varDecl.isImplicit){
				if(local.containsKey(varDecl) == false){
					local.put(varDecl, varDecl.createVar());
				}
			}
			return local.get(varDecl);
		}
	}
	
	public VbVariable locateVbVariable(VarDecl varDecl) {
		return this.locateVbVariable(varDecl, null);
	}

	public VbValue getFunctionResult() {
		return local.get(method.result).value;
	}

	public ModuleInstance locateRuntimeModule(ModuleMemberDecl decl) {
		RuntimeLibrary runtimeLib = (RuntimeLibrary) libs.get(decl.getLibrary().upperCaseName());
		return runtimeLib.modules.get(decl.module.upperCaseName());
	}

	public MethodDecl locateVbMethod(VbDecl decl) {
		return (MethodDecl) decl;
	}

	@Override
	public String toString() {
		return String.format("Call Frame @(%s) \r\n%s", method.name, StringUtils.join(local.values(), "\r\n"));
	}

	public Statement getCurrentStatement() {
		return this.method.statements.get(this.statementIndex);
	}

	public Map<VarDecl, VbVariable> getVariables(){
		return MapUtils.unmodifiableMap(this.local);
	}
}
