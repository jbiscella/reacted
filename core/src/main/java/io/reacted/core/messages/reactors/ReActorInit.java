/*
 * Copyright (c) 2020 , <Pierre Falda> [ pierre@reacted.io ]
 * All rights reserved.
 *
 * This source code is licensed under the BSD-style license found in the
 * LICENSE file in the root directory of this source tree.
 */

package io.reacted.core.messages.reactors;

import io.reacted.patterns.NonNullByDefault;

import javax.annotation.concurrent.Immutable;
import java.io.Externalizable;
import java.io.ObjectInput;
import java.io.ObjectOutput;

@Immutable
@NonNullByDefault
public final class ReActorInit implements Externalizable {

    private static final long serialVersionUID = 1;

    @Override
    public void writeExternal(ObjectOutput out) { }

    @Override
    public void readExternal(ObjectInput in) { }
}
