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

import org.siphon.visualbasic.compile.CompileException;
import org.siphon.visualbasic.compile.Compiler;
import org.siphon.visualbasic.compile.ImplementorClassModuleDecl;
import org.siphon.visualbasic.runtime.VbVarType;
import org.siphon.visualbasic.runtime.framework.Enums.VbCallType;
import vba.VbaParser.EventStmtContext;
import vba.VbaParser.ImplementsStmtContext;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class ClassModuleDecl extends ModuleDecl {

	protected MeDecl _me;
	public Map<String, EventDecl> events = new HashMap<>();
	protected VarDecl baseObject;

	private ModuleMemberDecl defaultMember;
	private ModuleMemberDecl iteratorMember;
	private ModuleMemberDecl dictionaryMember;
	public List<ClassTypeDecl> implementClasses = new ArrayList<>();
	
	private Map<ClassModuleDecl, ClassTypeDecl> implementors = new HashMap<>();
	protected Compiler compiler;
	
	public VarDecl getBaseObject() {
		return baseObject;
	}

	public ClassModuleDecl(Library lib, Compiler compiler) {
		super(lib);
		this.compiler = compiler;
		this.moduleType = ModuleType.ClassModule;

		MeDecl me = new MeDecl(lib, this);
		me.varType = new VbVarType(VbVarType.vbObject, new ClassTypeDecl(lib, this), null, null);
		this._me = me;

		addTheBaseObjDecl(); // 增加一个成员 Class，用于 Class_Initialize, Class_Terminate
	}

	public ClassModuleDecl() {
		super(null);
	}

	protected void addTheBaseObjDecl() {
		if (this instanceof TheClass)
			return;

		VarDecl c = new VarDecl(this.library, this);
		c.name = "Class";
		c.withEvents = true;
		c.visibility = Visibility.PRIVATE;	// TODO HIDDEN
		c.withNew = true;

		ClassTypeDecl classTypeDecl = new ClassTypeDecl(library, new TheClass(library, compiler));
		c.varType = new VbVarType(VbVarType.vbObject, classTypeDecl, null, null);

		this.addMember(c);

		this.baseObject = c;
	}

	public MeDecl me() {
		return _me;
	}

	public void addEvent(EventStmtContext eventStmt, EventDecl eventDecl) {
		if (this.events.containsKey(eventDecl.upperCaseName())) {
			this.errors.add(this.newCompileException(eventStmt.ambiguousIdentifier(), CompileException.AMBIGUOUS_IDENTIFIER,
					eventStmt.ambiguousIdentifier()));
		} else {
			this.events.put(eventDecl.upperCaseName(), eventDecl);
		}
	}

	public void setDefaultMember(String memberName) {
		this.defaultMember = (ModuleMemberDecl) this.members.get(memberName.toUpperCase());
	}

	public ModuleMemberDecl getDefaultMember() {
		return this.defaultMember;
	}
	
	public ModuleMemberDecl getDefaultMember(int callType) {
		ModuleMemberDecl defaultMember = this.getDefaultMember();
		if (defaultMember != null) {
			if (callType == VbCallType.VbMethod && defaultMember instanceof MethodDecl) {
				return defaultMember;
			} else if (defaultMember instanceof PropertyDecl) {
				PropertyDecl p = (PropertyDecl) defaultMember;
				if (p.get != null && callType == VbCallType.VbGet) {
					return p.get;
				} else if(p.let != null && callType == VbCallType.VbLet) {
					return p.let;
				} else if(p.set != null && callType == VbCallType.VbSet) {
					return p.set;
				}
			}
		}
		return null;
	}

	public void setIteratorMember(String memberName) {
		this.iteratorMember = (ModuleMemberDecl) this.members.get(memberName.toUpperCase());
	}

	public ModuleMemberDecl getIteratorMember() {
		return this.iteratorMember;
	}

	public void setDictionaryMember(String memberName) {
		this.dictionaryMember = (ModuleMemberDecl) this.members.get(memberName.toUpperCase());
	}

	public ModuleMemberDecl getDictionaryMember() {
		return this.dictionaryMember;
	}

	public boolean isImplementFrom(ClassModuleDecl classModuleDecl) {
		for (ClassTypeDecl impl : this.implementClasses) {
			if (impl.classModule == classModuleDecl)
				return true;
			else if (impl.classModule.isImplementFrom(classModuleDecl)){
				return true;
			}
		}
		return false;
	}

	public void buildImplements(List<ImplementsStmtContext> referenceAsts) {
		for(ClassTypeDecl impl : implementClasses){
			for(ImplementsStmtContext ast : referenceAsts){
				if(ast.ambiguousIdentifier().getText().equalsIgnoreCase(impl.name)){
					try {
						buildImplements(impl, ast);
					} catch (CompileException e) {
						this.addCompileException(e);
					}
				}
			}
		}
	}
	
	public ClassTypeDecl getImplementorTypeDecl(ClassModuleDecl base){
		return this.implementors.get(base);
	}

	private void buildImplements(ClassTypeDecl implement, ImplementsStmtContext ast) throws CompileException {
		ClassModuleDecl implCls = implement.classModule;
		ImplementorClassModuleDecl implementor = new ImplementorClassModuleDecl(this.library, this.compiler);
		implementor.name = implement.name;
		implementor.visibility = implement.visibility;
		for(VbDecl implDecl : implCls.members.values()){
			if(implDecl.visibility == Visibility.PUBLIC){	// public 成员都需要实现
				String mirrorName = implCls.upperCaseName() + "_" + implDecl.upperCaseName();
				VbDecl myOverrider = this.members.get(mirrorName);
				if(myOverrider == null){
					throw this.newCompileException(ast, CompileException.IMPLEMENT_NOT_FOUND, implDecl.name, implCls.name);
				}
				boolean match = true;
				if(implDecl instanceof VarDecl){
					if(myOverrider instanceof PropertyDecl){
						PropertyDecl pd = (PropertyDecl) myOverrider;
						if(pd.get == null || (pd.let == null && pd.let == null)){
							match = false;
						}
						if(pd.getArguments().size() > 0){
							match = false;
						}
					} else {
						match = false;
					}
				} else if(implDecl instanceof RuleDecl){
					if(myOverrider instanceof RuleDecl == false){
						match = false;
					} else {
						match = Compiler.isArgumentDeclsMatch(((MethodDecl) implDecl).arguments, ((MethodDecl)myOverrider).arguments);
					}
				} else if(implDecl instanceof MethodDecl){
					if(myOverrider instanceof MethodDecl == false  || myOverrider instanceof RuleDecl){
						match = false;
					} else {
						match = Compiler.isArgumentDeclsMatch(((MethodDecl) implDecl).arguments, ((MethodDecl)myOverrider).arguments);
					}
				} else if(implDecl instanceof PropertyDecl){
					if(myOverrider instanceof PropertyDecl == false){
						match = false;
					} else {
						match = Compiler.isArgumentDeclsMatch(((PropertyDecl) implDecl).getArguments(), ((PropertyDecl)myOverrider).getArguments());
					}
				}
				if(!match){
					throw this.newCompileException(ast, CompileException.IMPLEMENT_NOT_MATCH, implDecl.name, implCls.name);
				}
				implementor.members.put(implDecl.upperCaseName(), myOverrider);
				implementor.mirrors.put(implDecl, myOverrider);
			}
		}
		this.implementors.put(implCls, new ClassTypeDecl(library, implementor));
	}
}
