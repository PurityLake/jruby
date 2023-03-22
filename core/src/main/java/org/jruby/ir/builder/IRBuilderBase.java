package org.jruby.ir.builder;

import org.jruby.EvalType;
import org.jruby.RubyInstanceConfig;
import org.jruby.RubySymbol;
import org.jruby.ext.coverage.CoverageData;
import org.jruby.ir.IRClosure;
import org.jruby.ir.IREvalScript;
import org.jruby.ir.IRFlags;
import org.jruby.ir.IRFor;
import org.jruby.ir.IRManager;
import org.jruby.ir.IRMethod;
import org.jruby.ir.IRModuleBody;
import org.jruby.ir.IRScope;
import org.jruby.ir.IRScopeType;
import org.jruby.ir.IRScriptBody;
import org.jruby.ir.instructions.AliasInstr;
import org.jruby.ir.instructions.BFalseInstr;
import org.jruby.ir.instructions.BNEInstr;
import org.jruby.ir.instructions.BNilInstr;
import org.jruby.ir.instructions.BTrueInstr;
import org.jruby.ir.instructions.BUndefInstr;
import org.jruby.ir.instructions.CallInstr;
import org.jruby.ir.instructions.GetClassVarContainerModuleInstr;
import org.jruby.ir.instructions.Instr;
import org.jruby.ir.instructions.JumpInstr;
import org.jruby.ir.instructions.LabelInstr;
import org.jruby.ir.instructions.LineNumberInstr;
import org.jruby.ir.instructions.LoadBlockImplicitClosureInstr;
import org.jruby.ir.instructions.NopInstr;
import org.jruby.ir.instructions.ReceiveArgBase;
import org.jruby.ir.instructions.ReceiveKeywordArgInstr;
import org.jruby.ir.instructions.ReceiveKeywordRestArgInstr;
import org.jruby.ir.instructions.ReceiveRestArgInstr;
import org.jruby.ir.instructions.ResultInstr;
import org.jruby.ir.instructions.RuntimeHelperCall;
import org.jruby.ir.instructions.SearchConstInstr;
import org.jruby.ir.instructions.SearchModuleForConstInstr;
import org.jruby.ir.instructions.TraceInstr;
import org.jruby.ir.instructions.ZSuperInstr;
import org.jruby.ir.operands.Boolean;
import org.jruby.ir.operands.CurrentScope;
import org.jruby.ir.operands.DepthCloneable;
import org.jruby.ir.operands.Fixnum;
import org.jruby.ir.operands.Hash;
import org.jruby.ir.operands.Integer;
import org.jruby.ir.operands.Label;
import org.jruby.ir.operands.MutableString;
import org.jruby.ir.operands.Nil;
import org.jruby.ir.operands.NullBlock;
import org.jruby.ir.operands.Operand;
import org.jruby.ir.operands.ScopeModule;
import org.jruby.ir.operands.Splat;
import org.jruby.ir.operands.Symbol;
import org.jruby.ir.operands.TemporaryClosureVariable;
import org.jruby.ir.operands.TemporaryCurrentModuleVariable;
import org.jruby.ir.operands.TemporaryVariable;
import org.jruby.ir.operands.UndefinedValue;
import org.jruby.ir.operands.Variable;
import org.jruby.runtime.CallType;
import org.jruby.runtime.RubyEvent;
import org.jruby.util.ByteList;
import org.jruby.util.KeyValuePair;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.EnumSet;
import java.util.LinkedList;
import java.util.List;
import java.util.function.Consumer;

import static org.jruby.ir.IRFlags.*;
import static org.jruby.ir.instructions.RuntimeHelperCall.Methods.IS_HASH_EMPTY;
import static org.jruby.runtime.CallType.FUNCTIONAL;
import static org.jruby.runtime.CallType.NORMAL;
import static org.jruby.runtime.ThreadContext.CALL_KEYWORD;
import static org.jruby.runtime.ThreadContext.CALL_KEYWORD_REST;

public class IRBuilderBase {
    private IRManager manager;
    protected final IRScope scope;
    protected final IRBuilderBase parent;
    protected final List<Instr> instructions;
    protected int coverageMode;
    protected IRBuilderBase variableBuilder;
    protected List<Object> argumentDescriptions;
    public boolean executesOnce = true;
    int temporaryVariableIndex = -1;
    private boolean needsYieldBlock = false;
    public boolean underscoreVariableSeen = false;
    int lastProcessedLineNum = -1;
    private Variable currentModuleVariable = null;

    // We do not need n consecutive line num instrs but only the last one in the sequence.
    // We set this flag to indicate that we need to emit a line number but have not yet.
    // addInstr will then appropriately add line info when it is called (which will never be
    // called by a linenum instr).
    boolean needsLineNumInfo = false;

    // SSS FIXME: Currently only used for retries -- we should be able to eliminate this
    // Stack of nested rescue blocks -- this just tracks the start label of the blocks
    final Deque<RescueBlockInfo> activeRescueBlockStack = new ArrayDeque<>(4);

    // Stack of ensure blocks that are currently active
    final Deque<EnsureBlockInfo> activeEnsureBlockStack = new ArrayDeque<>(4);

    // Stack of ensure blocks whose bodies are being constructed
    final Deque<EnsureBlockInfo> ensureBodyBuildStack = new ArrayDeque<>(4);

    // Combined stack of active rescue/ensure nestings -- required to properly set up
    // rescuers for ensure block bodies cloned into other regions -- those bodies are
    // rescued by the active rescuers at the point of definition rather than the point
    // of cloning.
    final Deque<Label> activeRescuers = new ArrayDeque<>(4);

    // Since we are processing ASTs, loop bodies are processed in depth-first manner
    // with outer loops encountered before inner loops, and inner loops finished before outer ones.
    //
    // So, we can keep track of loops in a loop stack which  keeps track of loops as they are encountered.
    // This lets us implement next/redo/break/retry easily for the non-closure cases.
    final Deque<IRLoop> loopStack = new LinkedList<>();


    // If set we know which kind of eval is being performed.  Beyond type it also prevents needing to
    // ask what scope type we are in.
    public EvalType evalType = null;

    // This variable is an out-of-band passing mechanism to pass the method name to the block the
    // method is attached to.  call/fcall will set this and iter building will pass it into the iter
    // builder and set it.
    RubySymbol methodName = null;

    // Current index to put next BEGIN blocks and other things at the front of this scope.
    // Note: in the case of multiple BEGINs this index slides forward so they maintain proper
    // execution order
    protected int afterPrologueIndex = 0;
    private TemporaryVariable yieldClosureVariable = null;

    EnumSet<IRFlags> flags;

    public IRBuilderBase(IRManager manager, IRScope scope, IRBuilderBase parent, IRBuilderBase variableBuilder) {
        this.manager = manager;
        this.scope = scope;
        this.parent = parent;
        this.instructions = new ArrayList<>(50);
        this.activeRescuers.push(Label.UNRESCUED_REGION_LABEL);
        this.coverageMode = parent == null ? CoverageData.NONE : parent.coverageMode;

        if (parent != null) executesOnce = parent.executesOnce;

        this.variableBuilder = variableBuilder;
        this.flags = IRScope.allocateInitialFlags(scope);
    }

    public void computeScopeFlagsFrom(List<Instr> instructions) {
        for (Instr i : instructions) {
            i.computeScopeFlags(scope, flags);
        }

        calculateClosureScopeFlags();

        if (computeNeedsDynamicScopeFlag()) flags.add(REQUIRES_DYNSCOPE);

        flags.add(FLAGS_COMPUTED);
    }

    private void calculateClosureScopeFlags() {
        // Compute flags for nested closures (recursively) and set derived flags.
        for (IRClosure cl : scope.getClosures()) {
            if (cl.usesEval()) {
                scope.setCanReceiveBreaks();
                scope.setCanReceiveNonlocalReturns();
                scope.setUsesZSuper();
            } else {
                if (cl.hasBreakInstructions() || cl.canReceiveBreaks()) scope.setCanReceiveBreaks();
                if (cl.hasNonLocalReturns() || cl.canReceiveNonlocalReturns()) scope.setCanReceiveNonlocalReturns();
                if (cl.usesZSuper()) scope.setUsesZSuper();
            }
        }
    }

    private boolean computeNeedsDynamicScopeFlag() {
        return scope.hasNonLocalReturns() ||
                scope.canCaptureCallersBinding() ||
                scope.canReceiveNonlocalReturns() ||
                flags.contains(BINDING_HAS_ESCAPED);
    }

    boolean hasListener() {
        return manager.getIRScopeListener() != null;
    }

    RubySymbol methodNameFor() {
        IRScope method = scope.getNearestMethod();

        return method == null ? null : method.getName();
    }


    IRLoop getCurrentLoop() {
        return loopStack.peek();
    }

    boolean needsCodeCoverage() {
        return coverageMode != CoverageData.NONE || parent != null && parent.needsCodeCoverage();
    }

    public void addInstr(Instr instr) {
        if (needsLineNumInfo) {
            needsLineNumInfo = false;

            if (needsCodeCoverage()) {
                addInstr(new LineNumberInstr(lastProcessedLineNum, coverageMode));
            } else {
                addInstr(manager.newLineNumber(lastProcessedLineNum));
            }

            if (RubyInstanceConfig.FULL_TRACE_ENABLED) {
                addInstr(new TraceInstr(RubyEvent.LINE, getCurrentModuleVariable(), methodNameFor(), getFileName(), lastProcessedLineNum + 1));
            }
        }

        // If we are building an ensure body, stash the instruction
        // in the ensure body's list. If not, add it to the scope directly.
        if (ensureBodyBuildStack.isEmpty()) {
            instr.computeScopeFlags(scope, flags);

            if (hasListener()) manager.getIRScopeListener().addedInstr(scope, instr, instructions.size());

            instructions.add(instr);
        } else {
            ensureBodyBuildStack.peek().addInstr(instr);
        }
    }

    public void addInstrAtBeginning(Instr instr) {
        // If we are building an ensure body, stash the instruction
        // in the ensure body's list. If not, add it to the scope directly.
        if (ensureBodyBuildStack.isEmpty()) {
            instr.computeScopeFlags(scope, flags);

            if (hasListener()) manager.getIRScopeListener().addedInstr(scope, instr, 0);

            instructions.add(0, instr);
        } else {
            ensureBodyBuildStack.peek().addInstrAtBeginning(instr);
        }
    }

    // Add the specified result instruction to the scope and return its result variable.
    Variable addResultInstr(ResultInstr instr) {
        addInstr((Instr) instr);

        return instr.getResult();
    }

    // Emit cloned ensure bodies by walking up the ensure block stack.
    // If we have been passed a loop value, only emit bodies that are nested within that loop.
    void emitEnsureBlocks(IRLoop loop) {
        int n = activeEnsureBlockStack.size();
        EnsureBlockInfo[] ebArray = activeEnsureBlockStack.toArray(new EnsureBlockInfo[n]);
        for (int i = 0; i < n; i++) { // Deque's head is the first element (unlike Stack's)
            EnsureBlockInfo ebi = ebArray[i];

            // For "break" and "next" instructions, we only want to run
            // ensure blocks from the loops they are present in.
            if (loop != null && ebi.innermostLoop != loop) break;

            // Clone into host scope
            ebi.cloneIntoHostScope(this);
        }
    }

    private boolean isDefineMethod() {
        if (methodName != null) {
            String name = methodName.asJavaString();

            return "define_method".equals(name) || "define_singleton_method".equals(name);
        }

        return false;
    }

    // FIXME: Technically a binding in top-level could get passed which would should still cause an error but this
    //   scenario is very uncommon combined with setting @@cvar in a place you shouldn't it is an acceptable incompat
    //   for what I consider to be a very low-value error.
    boolean isTopScope() {
        IRScope topScope = scope.getNearestNonClosurelikeScope();

        boolean isTopScope = topScope instanceof IRScriptBody ||
                (evalType != null && evalType != EvalType.MODULE_EVAL && evalType != EvalType.BINDING_EVAL);

        // we think it could be a top scope but it could still be called from within a module/class which
        // would then not be a top scope.
        if (!isTopScope) return false;

        IRScope s = topScope;
        while (s != null && !(s instanceof IRModuleBody)) {
            s = s.getLexicalParent();
        }

        return s == null; // nothing means we walked all the way up.
    }

    void preloadBlockImplicitClosure() {
        if (needsYieldBlock) {
            addInstrAtBeginning(new LoadBlockImplicitClosureInstr(getYieldClosureVariable()));
        }
    }

    private TemporaryVariable createTemporaryVariable() {
        // BEGIN uses its parent builder to store any variables
        if (variableBuilder != null) return variableBuilder.createTemporaryVariable();

        temporaryVariableIndex++;

        if (scope.getScopeType() == IRScopeType.CLOSURE) {
            return new TemporaryClosureVariable(((IRClosure) scope).closureId, temporaryVariableIndex);
        } else {
            return manager.newTemporaryLocalVariable(temporaryVariableIndex);
        }
    }

    // FIXME: Add this to clone on branch instrs so if something changes (like an inline) it will replace with opted branch/jump/nop.
    public static Instr createBranch(Operand v1, Operand v2, Label jmpTarget) {
        if (v2 instanceof Boolean) {
            Boolean lhs = (Boolean) v2;

            if (lhs.isTrue()) {
                if (v1.isTruthyImmediate()) return new JumpInstr(jmpTarget);
                if (v1.isFalseyImmediate()) return NopInstr.NOP;

                return new BTrueInstr(jmpTarget, v1);
            } else if (lhs.isFalse()) {
                if (v1.isTruthyImmediate()) return NopInstr.NOP;
                if (v1.isFalseyImmediate()) return new JumpInstr(jmpTarget);

                return new BFalseInstr(jmpTarget, v1);
            }
        } else if (v2 instanceof Nil) {
            if (v1 instanceof Nil) return new JumpInstr(jmpTarget);
            if (v1.isTruthyImmediate()) return NopInstr.NOP;

            return new BNilInstr(jmpTarget, v1);
        }
        if (v2 == UndefinedValue.UNDEFINED) {
            if (v1 == UndefinedValue.UNDEFINED) return new JumpInstr(jmpTarget);

            return new BUndefInstr(jmpTarget, v1);
        }

        throw new RuntimeException("BUG: no BEQ");
    }

    public static void determineZSuperCallArgs(IRScope scope, IRBuilderBase builder, List<Operand> callArgs, List<KeyValuePair<Operand, Operand>> keywordArgs) {
        if (builder != null) {  // Still in currently building scopes
            for (Instr instr : builder.instructions) {
                extractCallOperands(callArgs, keywordArgs, instr);
            }
        } else {               // walked out past the eval to already build scopes
            for (Instr instr : scope.getInterpreterContext().getInstructions()) {
                extractCallOperands(callArgs, keywordArgs, instr);
            }
        }
    }

    /*
     * Adjust all argument operands by changing their depths to reflect how far they are from
     * super.  This fixup is only currently happening in supers nested in closures.
     */
    private Operand[] adjustVariableDepth(Operand[] args, int depthFromSuper) {
        if (depthFromSuper == 0) return args;

        Operand[] newArgs = new Operand[args.length];

        for (int i = 0; i < args.length; i++) {
            // Because of keyword args, we can have a keyword-arg hash in the call args.
            if (args[i] instanceof Hash) {
                newArgs[i] = ((Hash) args[i]).cloneForLVarDepth(depthFromSuper);
            } else {
                newArgs[i] = ((DepthCloneable) args[i]).cloneForDepth(depthFromSuper);
            }
        }

        return newArgs;
    }

    public static Operand[] addArg(Operand[] args, Operand extraArg) {
        Operand[] newArgs = new Operand[args.length + 1];
        System.arraycopy(args, 0, newArgs, 0, args.length);
        newArgs[args.length] = extraArg;
        return newArgs;
    }

    // No bounds checks.  Only call this when you know you have an arg to remove.
    public static Operand[] removeArg(Operand[] args) {
        Operand[] newArgs = new Operand[args.length - 1];
        System.arraycopy(args, 0, newArgs, 0, args.length - 1);
        return newArgs;
    }

    Operand searchModuleForConst(Operand startingModule, RubySymbol name) {
        return addResultInstr(new SearchModuleForConstInstr(temp(), startingModule, name, true));
    }

    Operand searchConst(RubySymbol name) {
        return addResultInstr(new SearchConstInstr(temp(), CurrentScope.INSTANCE, name, false));
    }

    // SSS FIXME: This feels a little ugly.  Is there a better way of representing this?
    public Operand classVarContainer(boolean declContext) {
        /* -------------------------------------------------------------------------------
         * We are looking for the nearest enclosing scope that is a non-singleton class body
         * without running into an eval-scope in between.
         *
         * Stop lexical scope walking at an eval script boundary.  Evals are essentially
         * a way for a programmer to splice an entire tree of lexical scopes at the point
         * where the eval happens.  So, when we hit an eval-script boundary at compile-time,
         * defer scope traversal to when we know where this scope has been spliced in.
         * ------------------------------------------------------------------------------- */
        int n = 0;
        IRScope cvarScope = scope;
        while (cvarScope != null && !(cvarScope instanceof IREvalScript) && !cvarScope.isNonSingletonClassBody()) {
            // For loops don't get their own static scope
            if (!(cvarScope instanceof IRFor)) {
                n++;
            }
            cvarScope = cvarScope.getLexicalParent();
        }

        if (cvarScope != null && cvarScope.isNonSingletonClassBody()) {
            return ScopeModule.ModuleFor(n);
        } else {
            return addResultInstr(new GetClassVarContainerModuleInstr(temp(),
                    CurrentScope.INSTANCE, declContext ? null : buildSelf()));
        }
    }

    Operand addRaiseError(String id, String message) {
        return addRaiseError(id, new MutableString(message));
    }

    Operand addRaiseError(String id, Operand message) {
        Operand exceptionClass = searchModuleForConst(getManager().getObjectClass(), symbol(id));
        Operand kernel = searchModuleForConst(getManager().getObjectClass(), symbol("Kernel"));
        return call(temp(), kernel, "raise", exceptionClass, message);
    }

    static void extractCallOperands(List<Operand> callArgs, List<KeyValuePair<Operand, Operand>> keywordArgs, Instr instr) {
        if (instr instanceof ReceiveKeywordRestArgInstr) {
            // Always add the keyword rest arg to the beginning
            keywordArgs.add(0, new KeyValuePair<>(Symbol.KW_REST_ARG_DUMMY, ((ReceiveArgBase) instr).getResult()));
        } else if (instr instanceof ReceiveKeywordArgInstr) {
            ReceiveKeywordArgInstr receiveKwargInstr = (ReceiveKeywordArgInstr) instr;
            keywordArgs.add(new KeyValuePair<>(new Symbol(receiveKwargInstr.getKey()), receiveKwargInstr.getResult()));
        } else if (instr instanceof ReceiveRestArgInstr) {
            callArgs.add(new Splat(((ReceiveRestArgInstr) instr).getResult()));
        } else if (instr instanceof ReceiveArgBase) {
            callArgs.add(((ReceiveArgBase) instr).getResult());
        }
    }

    // for simple calls without splats or keywords
    Variable call(Variable result, Operand object, String name, Operand... args) {
        return call(result, object, symbol(name), args);
    }

    // for simple calls without splats or keywords
    Variable call(Variable result, Operand object, RubySymbol name, Operand... args) {
        return _call(result, NORMAL, object, name, args);
    }

    Variable _call(Variable result, CallType type, Operand object, RubySymbol name, Operand... args) {
        addInstr(CallInstr.create(scope, type, result, name, object, args, NullBlock.INSTANCE, 0));
        return result;
    }

    // if-only
    private void cond(Label label, Operand value, Operand test) {
        addInstr(createBranch(value, test, label));
    }

    // if with body
    void cond(Label endLabel, Operand value, Operand test, RunIt body) {
        addInstr(createBranch(value, test, endLabel));
        body.apply();
    }

    // if-only
    void cond_ne(Label label, Operand value, Operand test) {
        addInstr(BNEInstr.create(label, value, test));
    }
    // if !test/else
    void cond_ne(Label endLabel, Operand value, Operand test, RunIt body) {
        addInstr(BNEInstr.create(endLabel, value, test));
        body.apply();
    }


    Boolean fals() {
        return manager.getFalse();
    }

    // for simple calls without splats or keywords
    Variable fcall(Variable result, Operand object, String name, Operand... args) {
        return fcall(result, object, symbol(name), args);
    }

    // for simple calls without splats or keywords
    Variable fcall(Variable result, Operand object, RubySymbol name, Operand... args) {
        return _call(result, FUNCTIONAL, object, name, args);
    }

    Fixnum fix(long value) {
        return manager.newFixnum(value);
    }

    /**
     * Generate if testVariable NEQ testValue { ifBlock } else { elseBlock }.
     *
     * @param testVariable what we will test against testValue
     * @param testValue    what we want to testVariable to NOT be equal to.
     * @param ifBlock      the code if test values do NOT match
     * @param elseBlock    the code to execute otherwise.
     */
    void if_else(Operand testVariable, Operand testValue, VoidCodeBlock ifBlock, VoidCodeBlock elseBlock) {
        Label elseLabel = getNewLabel();
        Label endLabel = getNewLabel();

        addInstr(BNEInstr.create(elseLabel, testVariable, testValue));
        ifBlock.run();
        addInstr(new JumpInstr(endLabel));

        addInstr(new LabelInstr(elseLabel));
        elseBlock.run();
        addInstr(new LabelInstr(endLabel));
    }

    void if_not(Operand testVariable, Operand testValue, VoidCodeBlock ifBlock) {
        label("if_not_end", (endLabel) -> {
            addInstr(createBranch(testVariable, testValue, endLabel));
            ifBlock.run();
        });
    }

    // Standard for loop in IR.  'test' is responsible for jumping if it fails.
    void for_loop(Consumer<Label> test, Consumer<Label> increment, Consume2<Label, Label> body) {
        Label top = getNewLabel("for_top");
        Label bottom = getNewLabel("for_bottom");
        label("for_end", after -> {
            addInstr(new LabelInstr(top));
            test.accept(after);
            body.apply(after, bottom);
            addInstr(new LabelInstr(bottom));
            increment.accept(after);
            jump(top);
        });
    }

    void label(String labelName, Consumer<Label> block) {
        Label label = getNewLabel(labelName);
        block.accept(label);
        addInstr(new LabelInstr(label));
    }

    Nil nil() {
        return manager.getNil();
    }

    RubySymbol symbol(String id) {
        return manager.runtime.newSymbol(id);
    }

    RubySymbol symbol(ByteList bytelist) {
        return manager.runtime.newSymbol(bytelist);
    }

    Variable temp() {
        return createTemporaryVariable();
    }

    void type_error(String message) {
        addRaiseError("TypeError", message);
    }



    void jump(Label label) {
        addInstr(new JumpInstr(label));
    }

    // Create an unrolled loop of expressions passing in the label which marks the end of these tests.
    void times(int times, Consume2<Label, Integer> body) {
        label("times_end", end -> {
            for (int i = 0; i < times; i++) {
                body.apply(end, new Integer(i));
            }
        });
    }

    Boolean tru() {
        return manager.getTrue();
    }

    public Variable createCurrentModuleVariable() {
        // SSS: Used in only 3 cases in generated IR:
        // -> searching a constant in the inheritance hierarchy
        // -> searching a super-method in the inheritance hierarchy
        // -> looking up 'StandardError' (which can be eliminated by creating a special operand type for this)
        temporaryVariableIndex++;
        return TemporaryCurrentModuleVariable.ModuleVariableFor(temporaryVariableIndex);
    }

    public Variable getCurrentModuleVariable() {
        if (currentModuleVariable == null) currentModuleVariable = createCurrentModuleVariable();

        return currentModuleVariable;
    }

    String getFileName() {
        return scope.getFile();
    }

    public RubySymbol getName() {
        return scope.getName();
    }

    Label getNewLabel() {
        return scope.getNewLabel();
    }

    Label getNewLabel(String labelName) {
        return scope.getNewLabel(labelName);
    }

    /**
     * Get the variable for accessing the "yieldable" closure in this scope.
     */
    public TemporaryVariable getYieldClosureVariable() {
        // make sure we prepare yield block for this scope, since it is now needed
        needsYieldBlock = true;

        if (yieldClosureVariable == null) {
            return yieldClosureVariable = createTemporaryVariable();
        }

        return yieldClosureVariable;
    }

    static Operand[] getZSuperCallOperands(IRScope scope, List<Operand> callArgs, List<KeyValuePair<Operand, Operand>> keywordArgs, int[] flags) {
        if (scope.getNearestTopLocalVariableScope().receivesKeywordArgs()) {
            flags[0] |= CALL_KEYWORD;
            int i = 0;
            Operand[] args = new Operand[callArgs.size() + 1];
            for (Operand arg : callArgs) {
                args[i++] = arg;
            }
            args[i] = new Hash(keywordArgs, false);
            return args;
        }

        return callArgs.toArray(new Operand[callArgs.size()]);
    }

    // build methods

    public Operand buildAlias(Operand newName, Operand oldName) {
        addInstr(new AliasInstr(newName, oldName));

        return nil();
    }

    public Variable buildSelf() {
        return scope.getSelf();
    }

    Operand buildZSuperIfNest(final Operand block) {
        int depthFrom = 0;
        IRBuilderBase superBuilder = this;
        IRScope superScope = scope;

        boolean defineMethod = false;
        // Figure out depth from argument scope and whether defineMethod may be one of the method calls.
        while (superScope instanceof IRClosure) {
            if (superBuilder != null && superBuilder.isDefineMethod()) defineMethod = true;

            // We may run out of live builds and walk int already built scopes if zsuper in an eval
            superBuilder = superBuilder != null && superBuilder.parent != null ? superBuilder.parent : null;
            superScope = superScope.getLexicalParent();
            depthFrom++;
        }

        final int depthFromSuper = depthFrom;

        // If we hit a method, this is known to always succeed
        Variable zsuperResult = temp();
        if (superScope instanceof IRMethod && !defineMethod) {
            List<Operand> callArgs = new ArrayList<>(5);
            List<KeyValuePair<Operand, Operand>> keywordArgs = new ArrayList<>(3);
            int[] flags = new int[]{0};
            determineZSuperCallArgs(superScope, superBuilder, callArgs, keywordArgs);

            if (keywordArgs.size() == 1 && keywordArgs.get(0).getKey().equals(Symbol.KW_REST_ARG_DUMMY)) {
                flags[0] |= (CALL_KEYWORD | CALL_KEYWORD_REST);
                Operand keywordRest = ((DepthCloneable) keywordArgs.get(0).getValue()).cloneForDepth(depthFromSuper);
                Operand[] args = adjustVariableDepth(callArgs.toArray(new Operand[callArgs.size()]), depthFromSuper);
                Variable test = addResultInstr(new RuntimeHelperCall(temp(), IS_HASH_EMPTY, new Operand[]{keywordRest}));
                if_else(test, tru(),
                        () -> addInstr(new ZSuperInstr(scope, zsuperResult, buildSelf(), args, block, flags[0], scope.maybeUsingRefinements())),
                        () -> addInstr(new ZSuperInstr(scope, zsuperResult, buildSelf(), addArg(args, keywordRest), block, flags[0], scope.maybeUsingRefinements())));
            } else {
                Operand[] args = adjustVariableDepth(getZSuperCallOperands(scope, callArgs, keywordArgs, flags), depthFromSuper);
                addInstr(new ZSuperInstr(scope, zsuperResult, buildSelf(), args, block, flags[0], scope.maybeUsingRefinements()));
            }
        } else {
            // We will not have a zsuper show up since we won't emit it but we still need to toggle it.
            // define_method optimization will try and create a method from a closure but it should not in this case.
            scope.setUsesZSuper();

            // Two conditions will inject an error:
            // 1. We cannot find any method scope above the closure (e.g. module A; define_method(:a) { super }; end)
            // 2. One of the method calls the closure is passed to is named define_method.
            //
            // Note: We are introducing an issue but it is so obscure we are ok with it.
            // A method named define_method containing zsuper in a method scope which is not actually
            // a define_method will get raised as invalid even though it should zsuper to the method.
            addRaiseError("RuntimeError",
                    "implicit argument passing of super from method defined by define_method() is not supported. Specify all arguments explicitly.");
        }

        return zsuperResult;
    }

    public IRManager getManager() {
        return manager;
    }

    interface CodeBlock {
        Operand run();
    }

    interface Consume2<T, U> {
        void apply(T t, U u);
    }

    interface RunIt {
        void apply();
    }

    interface VoidCodeBlock {
        void run();
    }
}

