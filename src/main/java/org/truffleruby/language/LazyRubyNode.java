/*
 * Copyright (c) 2016, 2017 Oracle and/or its affiliates. All rights reserved. This
 * code is released under a tri EPL/GPL/LGPL license. You can use it,
 * redistribute it and/or modify it under the terms of the:
 *
 * Eclipse Public License version 1.0
 * GNU General Public License version 2
 * GNU Lesser General Public License version 2.1
 */
package org.truffleruby.language;

import com.oracle.truffle.api.CompilerDirectives;
import com.oracle.truffle.api.frame.VirtualFrame;
import com.oracle.truffle.api.instrumentation.SourceSectionFilter;
import com.oracle.truffle.api.source.SourceSection;
import org.truffleruby.Log;
import org.truffleruby.RubyContext;
import org.truffleruby.RubyLanguage;

import java.util.function.Supplier;

public class LazyRubyNode extends RubyNode {

    public @interface LazyTag {
    }

    private final Supplier<RubyNode> resolver;
    private volatile RubyNode resolved = null;

    public LazyRubyNode(Supplier<RubyNode> resolver) {
        this.resolver = resolver;
    }

    @Override
    public Object execute(VirtualFrame frame) {
        return resolve().execute(frame);
    }

    @Override
    public SourceSection getSourceSection() {
        return resolve().getSourceSection();
    }

    @Override
    protected boolean isTaggedWith(Class<?> tag) {
        if (tag == LazyTag.class) {
            resolve();
            return true;
        }

        return resolve().isTaggedWith(tag);
    }

    public RubyNode resolve() {
        CompilerDirectives.transferToInterpreterAndInvalidate();

        return atomic(() -> {
            if (resolved != null) {
                return resolved;
            }

            if (getContext().getOptions().LAZY_TRANSLATION_LOG) {
                Log.LOGGER.info(() -> "lazy translating " + RubyLanguage.fileLine(getParent().getEncapsulatingSourceSection()) + " in " + getRootNode());
            }

            final RubyNode result = resolver.get();
            transferFlagsTo(result);

            // publish
            resolved = result;
            replace(result, "lazy node resolved");
            return result;
        });
    }

    public static void resolveAll(RubyContext context) {
        context.getInstrumenter().querySourceSections(SourceSectionFilter.newBuilder()
                .mimeTypeIs(RubyLanguage.MIME_TYPE)
                .tagIs(LazyRubyNode.LazyTag.class)
                .build());
    }

}
