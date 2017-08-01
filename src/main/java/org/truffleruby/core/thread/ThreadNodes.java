/*
 * Copyright (c) 2013, 2017 Oracle and/or its affiliates. All rights reserved. This
 * code is released under a tri EPL/GPL/LGPL license. You can use it,
 * redistribute it and/or modify it under the terms of the:
 *
 * Eclipse Public License version 1.0
 * GNU General Public License version 2
 * GNU Lesser General Public License version 2.1
 *
 * Version: EPL 1.0/GPL 2.0/LGPL 2.1
 *
 * The contents of this file are subject to the Eclipse Public
 * License Version 1.0 (the "License"); you may not use this file
 * except in compliance with the License. You may obtain a copy of
 * the License at http://www.eclipse.org/legal/epl-v10.html
 *
 * Software distributed under the License is distributed on an "AS
 * IS" basis, WITHOUT WARRANTY OF ANY KIND, either express or
 * implied. See the License for the specific language governing
 * rights and limitations under the License.
 *
 * Copyright (C) 2002 Jason Voegele <jason@jvoegele.com>
 * Copyright (C) 2002-2004 Anders Bengtsson <ndrsbngtssn@yahoo.se>
 * Copyright (C) 2002-2004 Jan Arne Petersen <jpetersen@uni-bonn.de>
 * Copyright (C) 2004 Thomas E Enebo <enebo@acm.org>
 * Copyright (C) 2004-2005 Charles O Nutter <headius@headius.com>
 * Copyright (C) 2004 Stefan Matthias Aust <sma@3plus4.de>
 *
 * Alternatively, the contents of this file may be used under the terms of
 * either of the GNU General Public License Version 2 or later (the "GPL"),
 * or the GNU Lesser General Public License Version 2.1 or later (the "LGPL"),
 * in which case the provisions of the GPL or the LGPL are applicable instead
 * of those above. If you wish to allow use of your version of this file only
 * under the terms of either the GPL or the LGPL, and not to allow others to
 * use your version of this file under the terms of the EPL, indicate your
 * decision by deleting the provisions above and replace them with the notice
 * and other provisions required by the GPL or the LGPL. If you do not delete
 * the provisions above, a recipient may use your version of this file under
 * the terms of any one of the EPL, the GPL or the LGPL.
 */
package org.truffleruby.core.thread;

import com.oracle.truffle.api.CompilerDirectives;
import com.oracle.truffle.api.CompilerDirectives.CompilationFinal;
import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;
import com.oracle.truffle.api.dsl.Cached;
import com.oracle.truffle.api.dsl.Specialization;
import com.oracle.truffle.api.frame.VirtualFrame;
import com.oracle.truffle.api.nodes.Node;
import com.oracle.truffle.api.object.DynamicObject;
import com.oracle.truffle.api.profiles.BranchProfile;
import com.oracle.truffle.api.source.SourceSection;

import org.jcodings.specific.USASCIIEncoding;
import org.jcodings.specific.UTF8Encoding;
import org.truffleruby.Layouts;
import org.truffleruby.RubyContext;
import org.truffleruby.RubyLanguage;
import org.truffleruby.builtins.CoreClass;
import org.truffleruby.builtins.CoreMethod;
import org.truffleruby.builtins.CoreMethodArrayArgumentsNode;
import org.truffleruby.builtins.NonStandard;
import org.truffleruby.builtins.Primitive;
import org.truffleruby.builtins.PrimitiveArrayArgumentsNode;
import org.truffleruby.builtins.UnaryCoreMethodNode;
import org.truffleruby.builtins.YieldingCoreMethodNode;
import org.truffleruby.collections.Memo;
import org.truffleruby.core.InterruptMode;
import org.truffleruby.core.array.ArrayOperations;
import org.truffleruby.core.exception.ExceptionOperations;
import org.truffleruby.core.proc.ProcOperations;
import org.truffleruby.core.rope.CodeRange;
import org.truffleruby.core.string.StringNodes;
import org.truffleruby.language.NotProvided;
import org.truffleruby.language.RubyNode;
import org.truffleruby.language.Visibility;
import org.truffleruby.language.backtrace.Backtrace;
import org.truffleruby.language.control.RaiseException;
import org.truffleruby.language.objects.AllocateObjectNode;
import org.truffleruby.language.objects.ReadObjectFieldNode;
import org.truffleruby.language.objects.ReadObjectFieldNodeGen;
import org.truffleruby.language.objects.shared.SharedObjects;
import org.truffleruby.language.yield.YieldNode;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

@CoreClass("Thread")
public abstract class ThreadNodes {

    @CoreMethod(names = "__allocate__", constructor = true, visibility = Visibility.PRIVATE)
    public abstract static class AllocateNode extends UnaryCoreMethodNode {

        @Specialization
        public DynamicObject allocate(DynamicObject rubyClass) {
            throw new RaiseException(coreExceptions().typeErrorAllocatorUndefinedFor(rubyClass, this));
        }

    }

    @CoreMethod(names = "alive?")
    public abstract static class AliveNode extends CoreMethodArrayArgumentsNode {

        @Specialization
        public boolean alive(DynamicObject thread) {
            final ThreadStatus status = Layouts.THREAD.getStatus(thread);
            return status != ThreadStatus.DEAD;
        }

    }

    @CoreMethod(names = "backtrace")
    public abstract static class BacktraceNode extends CoreMethodArrayArgumentsNode {

        @TruffleBoundary
        @Specialization
        public DynamicObject backtrace(DynamicObject rubyThread) {
            final Thread thread = Layouts.FIBER.getThread(Layouts.THREAD.getFiberManager(rubyThread).getCurrentFiber());

            final Memo<DynamicObject> result = new Memo<>(null);

            getContext().getSafepointManager().pauseThreadAndExecute(thread, this, (thread1, currentNode) -> {
                final Backtrace backtrace = getContext().getCallStack().getBacktrace(currentNode);
                result.set(ExceptionOperations.backtraceAsRubyStringArray(getContext(), null, backtrace));
            });

            // If the thread id dead or aborting the SafepointAction will not run

            if (result.get() != null) {
                return result.get();
            } else {
                return nil();
            }
        }

    }

    @CoreMethod(names = "current", onSingleton = true)
    public abstract static class CurrentNode extends CoreMethodArrayArgumentsNode {

        @Specialization
        public DynamicObject current(VirtualFrame frame,
                @Cached("create()") GetCurrentRubyThreadNode getCurrentRubyThreadNode) {
            return getCurrentRubyThreadNode.executeGetRubyThread(frame);
        }

    }

    @CoreMethod(names = "group")
    public abstract static class GroupNode extends CoreMethodArrayArgumentsNode {

        @Specialization
        public DynamicObject group(DynamicObject thread) {
            return Layouts.THREAD.getThreadGroup(thread);
        }

    }

    @CoreMethod(names = { "kill", "exit", "terminate" })
    public abstract static class KillNode extends CoreMethodArrayArgumentsNode {

        @TruffleBoundary
        @Specialization
        public DynamicObject kill(final DynamicObject rubyThread) {
            final Thread toKill = Layouts.THREAD.getThread(rubyThread);

            if (toKill == null) {
                // Already dead
                return rubyThread;
            }

            getContext().getSafepointManager().pauseThreadAndExecuteLater(toKill, this,
                    (currentThread, currentNode) -> ThreadManager.shutdown(getContext(), currentThread, currentNode));

            return rubyThread;
        }

    }

    @CoreMethod(names = "handle_interrupt", required = 2, needsBlock = true, visibility = Visibility.PRIVATE)
    public abstract static class HandleInterruptNode extends YieldingCoreMethodNode {

        @CompilationFinal private DynamicObject immediateSymbol;
        @CompilationFinal private DynamicObject onBlockingSymbol;
        @CompilationFinal private DynamicObject neverSymbol;

        private final BranchProfile errorProfile = BranchProfile.create();

        @Specialization(guards = { "isRubyClass(exceptionClass)", "isRubySymbol(timing)" })
        public Object handle_interrupt(DynamicObject self, DynamicObject exceptionClass, DynamicObject timing, DynamicObject block) {
            // TODO (eregon, 12 July 2015): should we consider exceptionClass?
            final InterruptMode newInterruptMode = symbolToInterruptMode(timing);

            final InterruptMode oldInterruptMode = Layouts.THREAD.getInterruptMode(self);
            Layouts.THREAD.setInterruptMode(self, newInterruptMode);
            try {
                return yield(block);
            } finally {
                Layouts.THREAD.setInterruptMode(self, oldInterruptMode);
            }
        }

        private InterruptMode symbolToInterruptMode(DynamicObject symbol) {
            if (symbol == getImmediateSymbol()) {
                return InterruptMode.IMMEDIATE;
            } else if (symbol == getOnBlockingSymbol()) {
                return InterruptMode.ON_BLOCKING;
            } else if (symbol == getNeverSymbol()) {
                return InterruptMode.NEVER;
            } else {
                errorProfile.enter();
                throw new RaiseException(coreExceptions().argumentError("invalid timing symbol", this));
            }
        }

        private DynamicObject getImmediateSymbol() {
            if (immediateSymbol == null) {
                CompilerDirectives.transferToInterpreterAndInvalidate();
                immediateSymbol = getSymbol("immediate");
            }

            return immediateSymbol;
        }

        private DynamicObject getOnBlockingSymbol() {
            if (onBlockingSymbol == null) {
                CompilerDirectives.transferToInterpreterAndInvalidate();
                onBlockingSymbol = getSymbol("on_blocking");
            }

            return onBlockingSymbol;
        }

        private DynamicObject getNeverSymbol() {
            if (neverSymbol == null) {
                CompilerDirectives.transferToInterpreterAndInvalidate();
                neverSymbol = getSymbol("never");
            }

            return neverSymbol;
        }

    }

    @Primitive(name = "thread_initialized?")
    public abstract static class ThreadIsInitializedNode extends PrimitiveArrayArgumentsNode {

        @TruffleBoundary
        @Specialization
        public boolean isInitialized(DynamicObject thread) {
            final DynamicObject rootFiber = Layouts.THREAD.getFiberManager(thread).getRootFiber();
            final CountDownLatch initializedLatch = Layouts.FIBER.getInitializedLatch(rootFiber);
            return initializedLatch.getCount() == 0;
        }

    }

    @Primitive(name = "thread_initialize")
    public abstract static class ThreadInitializeNode extends PrimitiveArrayArgumentsNode {

        @TruffleBoundary
        @Specialization
        public DynamicObject initialize(DynamicObject thread, DynamicObject arguments, DynamicObject block) {
            if (getContext().getOptions().SHARED_OBJECTS_ENABLED) {
                SharedObjects.shareDeclarationFrame(getContext(), block);
            }

            final Object[] args = ArrayOperations.toObjectArray(arguments);
            final SourceSection sourceSection = Layouts.PROC.getSharedMethodInfo(block).getSourceSection();
            final String info = RubyLanguage.fileLine(sourceSection);
            ThreadManager.initialize(thread, getContext(), this, info, () -> {
                final Object value = ProcOperations.rootCall(block, args);
                Layouts.THREAD.setValue(thread, value);
            });
            return nil();
        }

    }

    @CoreMethod(names = "join", optional = 1, lowerFixnum = 1)
    public abstract static class JoinNode extends CoreMethodArrayArgumentsNode {

        @Specialization
        public DynamicObject join(DynamicObject thread, NotProvided timeout) {
            doJoin(this, thread);
            return thread;
        }

        @Specialization(guards = "isNil(nil)")
        public DynamicObject join(DynamicObject thread, Object nil) {
            return join(thread, NotProvided.INSTANCE);
        }

        @Specialization
        public Object join(DynamicObject thread, int timeout) {
            return joinMillis(thread, timeout * 1000);
        }

        @Specialization
        public Object join(DynamicObject thread, double timeout) {
            return joinMillis(thread, (int) (timeout * 1000.0));
        }

        private Object joinMillis(DynamicObject self, int timeoutInMillis) {
            if (doJoinMillis(self, timeoutInMillis)) {
                return self;
            } else {
                return nil();
            }
        }

        @TruffleBoundary
        public static void doJoin(RubyNode currentNode, final DynamicObject thread) {
            currentNode.getContext().getThreadManager().runUntilResult(currentNode, new ThreadManager.BlockingAction<Boolean>() {

                @Override
                public Boolean block() throws InterruptedException {
                    Layouts.THREAD.getFinishedLatch(thread).await();
                    return SUCCESS;
                }

            });

            final DynamicObject exception = Layouts.THREAD.getException(thread);
            if (exception != null) {
                currentNode.getContext().getCoreExceptions().showExceptionIfDebug(exception);
                throw new RaiseException(exception);
            }
        }

        @TruffleBoundary
        private boolean doJoinMillis(final DynamicObject thread, final int timeoutInMillis) {
            final long start = System.currentTimeMillis();

            final boolean joined = getContext().getThreadManager().runUntilResult(this, () -> {
                long now = System.currentTimeMillis();
                long waited = now - start;
                if (waited >= timeoutInMillis) {
                    // We need to know whether countDown() was called and we do not want to block.
                    return Layouts.THREAD.getFinishedLatch(thread).getCount() == 0;
                }
                return Layouts.THREAD.getFinishedLatch(thread).await(timeoutInMillis - waited, TimeUnit.MILLISECONDS);
            });

            if (joined) {
                final DynamicObject exception = Layouts.THREAD.getException(thread);
                if (exception != null) {
                    getContext().getCoreExceptions().showExceptionIfDebug(exception);
                    throw new RaiseException(exception);
                }
            }

            return joined;
        }

    }

    @CoreMethod(names = "main", onSingleton = true)
    public abstract static class MainNode extends CoreMethodArrayArgumentsNode {

        @Specialization
        public DynamicObject main() {
            return getContext().getThreadManager().getRootThread();
        }

    }

    @CoreMethod(names = "pass", onSingleton = true)
    public abstract static class PassNode extends CoreMethodArrayArgumentsNode {

        @TruffleBoundary
        @Specialization
        public DynamicObject pass() {
            Thread.yield();
            return nil();
        }

    }

    @CoreMethod(names = "status")
    public abstract static class StatusNode extends CoreMethodArrayArgumentsNode {

        @Child private StringNodes.MakeStringNode makeStringNode = StringNodes.MakeStringNode.create();

        @Specialization
        public Object status(DynamicObject self) {
            // TODO: slightly hackish
            final ThreadStatus status = Layouts.THREAD.getStatus(self);
            if (status == ThreadStatus.DEAD) {
                if (Layouts.THREAD.getException(self) != null) {
                    return nil();
                } else {
                    return false;
                }
            }
            return makeStringNode.executeMake(status.toString().toLowerCase(), USASCIIEncoding.INSTANCE, CodeRange.CR_7BIT);
        }

    }

    @CoreMethod(names = "stop?")
    public abstract static class StopNode extends CoreMethodArrayArgumentsNode {

        @Specialization
        public boolean stop(DynamicObject self) {
            final ThreadStatus status = Layouts.THREAD.getStatus(self);
            return status == ThreadStatus.DEAD || status == ThreadStatus.SLEEP;
        }

    }

    @CoreMethod(names = "value")
    public abstract static class ValueNode extends CoreMethodArrayArgumentsNode {

        @Specialization
        public Object value(DynamicObject self) {
            JoinNode.doJoin(this, self);
            final Object value = Layouts.THREAD.getValue(self);
            assert value != null;
            return value;
        }

    }

    @CoreMethod(names = { "wakeup", "run" })
    public abstract static class WakeupNode extends CoreMethodArrayArgumentsNode {

        @TruffleBoundary
        @Specialization
        public DynamicObject wakeup(DynamicObject rubyThread,
                                    @Cached("new()") YieldNode yieldNode) {
            final DynamicObject currentFiber = Layouts.THREAD.getFiberManager(rubyThread).getCurrentFiber();
            final Thread thread = Layouts.FIBER.getThread(currentFiber);
            if (thread == null) {
                throw new RaiseException(coreExceptions().threadErrorKilledThread(this));
            }

            Layouts.THREAD.getWakeUp(rubyThread).set(true);

            // TODO: should only interrupt sleep
            getContext().getThreadManager().interrupt(thread);

            return rubyThread;
        }

    }

    @NonStandard
    @CoreMethod(names = "unblock", required = 2)
    public abstract static class UnblockNode extends YieldingCoreMethodNode {

        @TruffleBoundary
        @Specialization(guards = {"isRubyProc(unblocker)", "isRubyProc(runner)"})
        public Object unblock(DynamicObject thread, DynamicObject unblocker, DynamicObject runner) {
            return getContext().getThreadManager().runUntilResult(this,
                    () -> yield(runner),
                    () -> yield(unblocker));
        }

    }

    @CoreMethod(names = "abort_on_exception")
    public abstract static class AbortOnExceptionNode extends CoreMethodArrayArgumentsNode {

        @Specialization
        public boolean abortOnException(DynamicObject self) {
            return Layouts.THREAD.getAbortOnException(self);
        }

    }

    @CoreMethod(names = "abort_on_exception=", required = 1)
    public abstract static class SetAbortOnExceptionNode extends CoreMethodArrayArgumentsNode {

        @Specialization
        public DynamicObject setAbortOnException(DynamicObject self, boolean abortOnException) {
            Layouts.THREAD.setAbortOnException(self, abortOnException);
            return nil();
        }

    }

    @Primitive(name = "thread_allocate")
    public abstract static class ThreadAllocateNode extends PrimitiveArrayArgumentsNode {

        @Specialization
        public DynamicObject allocate(
                DynamicObject rubyClass,
                @Cached("create()") AllocateObjectNode allocateObjectNode,
                @Cached("createReadAbortOnExceptionNode()") ReadObjectFieldNode readAbortOnException) {
            return getContext().getThreadManager().createThread(rubyClass, allocateObjectNode, readAbortOnException);
        }

        protected ReadObjectFieldNode createReadAbortOnExceptionNode() {
            return ReadObjectFieldNodeGen.create("@abort_on_exception", false);
        }

    }

    @CoreMethod(names = "list", onSingleton = true)
    public abstract static class ListNode extends CoreMethodArrayArgumentsNode {

        @Specialization
        public DynamicObject list() {
            final Object[] threads = getContext().getThreadManager().getThreadList();
            return createArray(threads, threads.length);
        }
    }

    @Primitive(name = "thread_raise")
    public static abstract class ThreadRaisePrimitiveNode extends PrimitiveArrayArgumentsNode {

        @Specialization(guards = { "isRubyThread(thread)", "isRubyException(exception)" })
        public DynamicObject raise(DynamicObject thread, final DynamicObject exception) {
            raiseInThread(getContext(), thread, exception, this);
            return nil();
        }

        @TruffleBoundary
        public static void raiseInThread(RubyContext context, DynamicObject rubyThread, DynamicObject exception, Node currentNode) {
            // The exception will be shared with another thread
            SharedObjects.writeBarrier(context, exception);

            final Thread javaThread = Layouts.FIBER.getThread(Layouts.THREAD.getFiberManager(rubyThread).getCurrentFiber());

            context.getSafepointManager().pauseThreadAndExecuteLater(javaThread, currentNode, (currentThread, currentNode1) -> {
                if (Layouts.EXCEPTION.getBacktrace(exception) == null) {
                    Backtrace backtrace = context.getCallStack().getBacktrace(currentNode1);
                    Layouts.EXCEPTION.setBacktrace(exception, backtrace);
                }
                throw new RaiseException(exception);
            });
        }

    }

    @Primitive(name = "thread_source_location")
    public static abstract class ThreadSourceLocationNode extends PrimitiveArrayArgumentsNode {

        @Child private StringNodes.MakeStringNode makeStringNode = StringNodes.MakeStringNode.create();

        @Specialization(guards = "isRubyThread(thread)")
        public DynamicObject sourceLocation(DynamicObject thread) {
            return makeStringNode.executeMake(Layouts.THREAD.getSourceLocation(thread), UTF8Encoding.INSTANCE, CodeRange.CR_UNKNOWN);
        }
    }

    @Primitive(name = "thread_get_name")
    public static abstract class ThreadGetNamePrimitiveNode extends PrimitiveArrayArgumentsNode {

        @Specialization(guards = "isRubyThread(thread)")
        public DynamicObject getName(DynamicObject thread) {
            return Layouts.THREAD.getName(thread);
        }
    }

    @Primitive(name = "thread_set_name")
    public static abstract class ThreadSetNamePrimitiveNode extends PrimitiveArrayArgumentsNode {

        @Specialization(guards = "isRubyThread(thread)")
        public DynamicObject setName(DynamicObject thread, DynamicObject name) {
            Layouts.THREAD.setName(thread, name);
            return name;
        }
    }

    @Primitive(name = "thread_get_priority")
    public static abstract class ThreadGetPriorityPrimitiveNode extends PrimitiveArrayArgumentsNode {

        @Specialization(guards = "isRubyThread(thread)")
        public int getPriority(DynamicObject thread) {
            final Thread javaThread = Layouts.THREAD.getThread(thread);
            if (javaThread != null) {
                return javaThread.getPriority();
            } else {
                return Layouts.THREAD.getPriority(thread);
            }
        }

    }

    @Primitive(name = "thread_set_priority", lowerFixnum = 1)
    public static abstract class ThreadSetPriorityPrimitiveNode extends PrimitiveArrayArgumentsNode {

        @Specialization(guards = "isRubyThread(thread)")
        public int getPriority(DynamicObject thread, int javaPriority) {
            final Thread javaThread = Layouts.THREAD.getThread(thread);
            if (javaThread != null) {
                javaThread.setPriority(javaPriority);
            }
            Layouts.THREAD.setPriority(thread, javaPriority);
            return javaPriority;
        }

    }

    @Primitive(name = "thread_set_group")
    public static abstract class ThreadSetGroupPrimitiveNode extends PrimitiveArrayArgumentsNode {

        @Specialization(guards = "isRubyThread(thread)")
        public DynamicObject setGroup(DynamicObject thread, DynamicObject threadGroup) {
            Layouts.THREAD.setThreadGroup(thread, threadGroup);
            return threadGroup;
        }
    }

    @Primitive(name = "thread_get_fiber_locals")
    public static abstract class ThreadGetFiberLocalsNode extends PrimitiveArrayArgumentsNode {

        @Specialization(guards = "isRubyThread(thread)")
        public DynamicObject getFiberLocals(DynamicObject thread) {
            final DynamicObject fiber = Layouts.THREAD.getFiberManager(thread).getCurrentFiber();
            return Layouts.FIBER.getFiberLocals(fiber);
        }
    }

}
