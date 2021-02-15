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

import org.apache.commons.lang3.ObjectUtils;
import org.siphon.visualbasic.*;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;

public class ModuleInstance {

	public static final Object WAIT_NEW = new ModuleInstance(); // 用于 VbValue.value，指示对象是否有待创建

	private final ModuleDecl moduleDecl;

	public final Map<VarDecl, VbVariable> variables = new HashMap<>();

	public final VbValue _asVbValue;

	private List<EventSubscriber> eventSubscribers = new ArrayList<>();

	public VbValue asVbValue() {
		return this._asVbValue;
	}

	private ModuleInstance() {
		moduleDecl = null;
		this._asVbValue = null;
	}

	public ModuleInstance(ModuleDecl module) {
		this.moduleDecl = module;
		
		for (String name : module.members.keySet()) {
			VbDecl member = module.members.get(name);
			if (member instanceof VarDecl) {
				VarDecl varDecl = (VarDecl) member;
				this.variables.put(varDecl, varDecl.createVar());

			} else if (member instanceof MethodDecl) {
				MethodDecl methodDecl = (MethodDecl) member;
				for (VarDecl var : methodDecl.variables.values()) {
					if (var.isStatic) {
						this.variables.put(var, var.createVar());
					}
				}
			}
		}

		if (module instanceof ClassModuleDecl) {
			ClassModuleDecl classModule = (ClassModuleDecl) module;
			VbVarType t = new VbVarType(VbVarType.vbObject, new ClassTypeDecl(module.getLibrary(), classModule), null, null);
			this._asVbValue = new VbValue(t, this);

		} else {
			this._asVbValue = null;
		}
	}

	public ModuleDecl getModuleDecl() {
		return this.moduleDecl;
	}

	public VbValue getFieldValue(String name) {
		return variables.get(name.toUpperCase()).value;
	}

	public Object getMember(String name) {
		name = name.toUpperCase();
		if (this.variables.containsKey(name)) {
			return this.variables.get(name);
		} else {
			return this.moduleDecl.members.get(name);
		}
	}

	public Object getMember(String name, ClassModuleDecl requestClassModule) {
		if(requestClassModule == null){
			return this.getMember(name);
		}
		name = name.toUpperCase();
		if(requestClassModule.members.containsKey(name)){
			return requestClassModule.members.get(name);
		} else {
			return this.variables.get(name);
		}
	}
	
	public void initializeClass(Interpreter interpreter, CallFrame frame) throws VbRuntimeException {
		ClassModuleDecl classModule = (ClassModuleDecl) this.moduleDecl;
		VbVariable baseObjVar = this.variables.get(classModule.getBaseObject());
		if (baseObjVar == null)
			return;
		
		baseObjVar.value.ensureInstanceInited(interpreter, frame, null);
		ModuleInstance instance = (ModuleInstance) baseObjVar.value.value;

		instance.bindEventHandlers(baseObjVar.varDecl, this, interpreter, frame);

		ClassTypeDecl decl = (ClassTypeDecl) baseObjVar.varType.typeDecl;
		if(decl.classModule instanceof TheClass) {
			TheClass theClass = (TheClass) decl.classModule;
			instance.raiseEvent(theClass.initializeEvent, null, null, interpreter, frame);
		} else if(this.moduleDecl instanceof FormModuleDecl){
			// Form 并不在初始化时 Load
//			try {
//				form.load(interpreter, frame);
//			} catch (ArgumentException e) {
//				throw new VbRuntimeException(VbRuntimeException.不能加载或卸载该对象, e);
//			}
		}
	}

	public void addEventListener(ModuleInstance subscriber, EventDecl eventDecl, MethodDecl listener) {
		EventSubscriber es = new EventSubscriber(subscriber, eventDecl, listener);
		this.eventSubscribers.add(es);
	}

	public void removeEventListener(final ModuleInstance subscriber) {
		this.eventSubscribers.removeIf(new Predicate<EventSubscriber>() {

			@Override
			public boolean test(EventSubscriber t) {
				return ObjectUtils.equals(t.getSubscriber(), subscriber);
			}
		});
	}

	public void bindEventHandlers(VarDecl varDeclInSubscriber, ModuleInstance subscriber, Interpreter interpreter,
			CallFrame frame) {
		// bind events
		ClassModuleDecl classDecl = (ClassModuleDecl) this.getModuleDecl();
		for (EventDecl event : classDecl.events.values()) {
			String handlerName = varDeclInSubscriber.name + "_" + event.name;
			MethodDecl handler = (MethodDecl) subscriber.getMember(handlerName.toUpperCase());
			if (handler != null) {
				this.addEventListener(subscriber, event, handler);
			}
		}
	}

	public void raiseEvent(EventDecl eventDecl, List<VbValue> arguments, Interpreter interpreter, CallFrame frame)
			throws VbRuntimeException, ArgumentException {
		for (EventSubscriber subscriber : this.eventSubscribers) {
			if (subscriber.getEventDecl() == eventDecl) {
				if (arguments == null) {
					interpreter.callMethod(subscriber.getSubscriber(), subscriber.getListener());
				} else {
					interpreter.callMethod(subscriber.getSubscriber(), subscriber.getListener(), arguments.toArray());
				}
			}
		}
	}

	public void raiseEvent(EventDecl eventDecl, List<Statement> argumentStatements, List<VbValue> arguments,
			Interpreter interpreter, CallFrame frame) throws VbRuntimeException {
		for (EventSubscriber subscriber : this.eventSubscribers) {
			if (subscriber.getEventDecl() == eventDecl) {
				if (arguments == null) {
					interpreter.callMethod(subscriber.getSubscriber(), argumentStatements, subscriber.getListener());
				} else {
					interpreter.callMethod(subscriber.getSubscriber(), argumentStatements, subscriber.getListener(),
							arguments.toArray());
				}
			}
		}
	}

	@Override
	public String toString() {
		return String.format("(Instance %s)", this.moduleDecl.name);
	}
}
