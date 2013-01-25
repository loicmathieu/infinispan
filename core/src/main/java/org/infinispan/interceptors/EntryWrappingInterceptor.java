/*
 * Copyright 2011 Red Hat, Inc. and/or its affiliates.
 *
 * This is free software; you can redistribute it and/or modify it
 * under the terms of the GNU Lesser General Public License as
 * published by the Free Software Foundation; either version 2.1 of
 * the License, or (at your option) any later version.
 *
 * This software is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU
 * Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public
 * License along with this library; if not, write to the Free Software
 * Foundation, Inc., 51 Franklin Street, Fifth Floor, Boston, MA
 * 02110-1301 USA
 */

package org.infinispan.interceptors;

import org.infinispan.commands.AbstractVisitor;
import org.infinispan.commands.CommandsFactory;
import org.infinispan.commands.FlagAffectedCommand;
import org.infinispan.commands.read.GetKeyValueCommand;
import org.infinispan.commands.tx.CommitCommand;
import org.infinispan.commands.tx.PrepareCommand;
import org.infinispan.commands.write.ApplyDeltaCommand;
import org.infinispan.commands.write.ClearCommand;
import org.infinispan.commands.write.EvictCommand;
import org.infinispan.commands.write.InvalidateCommand;
import org.infinispan.commands.write.InvalidateL1Command;
import org.infinispan.commands.write.PutKeyValueCommand;
import org.infinispan.commands.write.PutMapCommand;
import org.infinispan.commands.write.RemoveCommand;
import org.infinispan.commands.write.ReplaceCommand;
import org.infinispan.commands.write.WriteCommand;
import org.infinispan.container.DataContainer;
import org.infinispan.container.EntryFactory;
import org.infinispan.container.entries.CacheEntry;
import org.infinispan.container.entries.InternalCacheEntry;
import org.infinispan.context.Flag;
import org.infinispan.context.InvocationContext;
import org.infinispan.context.SingleKeyNonTxInvocationContext;
import org.infinispan.context.impl.TxInvocationContext;
import org.infinispan.factories.annotations.Inject;
import org.infinispan.factories.annotations.Start;
import org.infinispan.interceptors.base.CommandInterceptor;
import org.infinispan.interceptors.locking.ClusteringDependentLogic;
import org.infinispan.statetransfer.StateConsumer;
import org.infinispan.transaction.LocalTransaction;
import org.infinispan.util.logging.Log;
import org.infinispan.util.logging.LogFactory;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;

/**
 * Interceptor in charge with wrapping entries and add them in caller's context.
 *
 * @author Mircea Markus
 * @since 5.1
 */
public class EntryWrappingInterceptor extends CommandInterceptor {

   private EntryFactory entryFactory;
   protected DataContainer dataContainer;
   protected ClusteringDependentLogic cdl;
   protected final EntryWrappingVisitor entryWrappingVisitor = new EntryWrappingVisitor();
   private CommandsFactory commandFactory;
   private boolean isUsingLockDelegation;
   private StateConsumer stateConsumer;       // optional

   private static final Log log = LogFactory.getLog(EntryWrappingInterceptor.class);
   private static final boolean trace = log.isTraceEnabled();

   @Override
   protected Log getLog() {
      return log;
   }

   @Inject
   public void init(EntryFactory entryFactory, DataContainer dataContainer, ClusteringDependentLogic cdl, CommandsFactory commandFactory, StateConsumer stateConsumer) {
      this.entryFactory = entryFactory;
      this.dataContainer = dataContainer;
      this.cdl = cdl;
      this.commandFactory = commandFactory;
      this.stateConsumer = stateConsumer;
   }

   @Start
   public void start() {
      isUsingLockDelegation = !cacheConfiguration.transaction().transactionMode().isTransactional() &&
            cacheConfiguration.locking().supportsConcurrentUpdates() && cacheConfiguration.clustering().cacheMode().isDistributed();
   }

   @Override
   public Object visitPrepareCommand(TxInvocationContext ctx, PrepareCommand command) throws Throwable {
      if (!ctx.isOriginLocal() || command.isReplayEntryWrapping()) {
         for (WriteCommand c : command.getModifications()) {
            c.acceptVisitor(ctx, entryWrappingVisitor);
         }
      }
      Object result = invokeNextInterceptor(ctx, command);
      if (command.isOnePhaseCommit()) {
         commitContextEntries(ctx, false, isFromStateTransfer(ctx));
      }
      return result;
   }

   @Override
   public Object visitCommitCommand(TxInvocationContext ctx, CommitCommand command) throws Throwable {
      try {
         return invokeNextInterceptor(ctx, command);
      } finally {
         commitContextEntries(ctx, false, isFromStateTransfer(ctx));
      }
   }

   @Override
   public final Object visitGetKeyValueCommand(InvocationContext ctx, GetKeyValueCommand command) throws Throwable {
      try {
         entryFactory.wrapEntryForReading(ctx, command.getKey());
         return invokeNextInterceptor(ctx, command);
      } finally {
         //needed because entries might be added in L1
         if (!ctx.isInTxScope())
            commitContextEntries(ctx, command.hasFlag(Flag.SKIP_OWNERSHIP_CHECK), false);
      }
   }

   @Override
   public final Object visitInvalidateCommand(InvocationContext ctx, InvalidateCommand command) throws Throwable {
      if (command.getKeys() != null) {
         for (Object key : command.getKeys()) {
            entryFactory.wrapEntryForRemove(ctx, key);
         }
      }
      return invokeNextAndApplyChanges(ctx, command);
   }

   @Override
   public final Object visitClearCommand(InvocationContext ctx, ClearCommand command) throws Throwable {
      for (InternalCacheEntry entry : dataContainer.entrySet())
         entryFactory.wrapEntryForClear(ctx, entry.getKey());
      return invokeNextAndApplyChanges(ctx, command);
   }

   @Override
   public Object visitInvalidateL1Command(InvocationContext ctx, InvalidateL1Command command) throws Throwable {
      for (Object key : command.getKeys()) {
        entryFactory.wrapEntryForRemove(ctx, key);
        if (trace)
           log.tracef("Entry to be removed: %s", ctx.getLookedUpEntries());
      }
      return invokeNextAndApplyChanges(ctx, command);
   }

   @Override
   public final Object visitPutKeyValueCommand(InvocationContext ctx, PutKeyValueCommand command) throws Throwable {
      if (shouldWrap(command.getKey(), ctx, command)) {
         entryFactory.wrapEntryForPut(ctx, command.getKey(), null, !command.isPutIfAbsent(), command);
      }
      return invokeNextAndApplyChanges(ctx, command);
   }

   private boolean shouldWrap(Object key, InvocationContext ctx, FlagAffectedCommand command) {
      if (command.hasFlag(Flag.SKIP_OWNERSHIP_CHECK)) {
         log.tracef("Skipping ownership check and wrapping key %s", key);
         return true;
      }
      boolean result;
      if (cacheConfiguration.transaction().transactionMode().isTransactional()) {
         result = true;
      } else {
         if (isUsingLockDelegation) {
            result = cdl.localNodeIsPrimaryOwner(key) || (cdl.localNodeIsOwner(key) && !ctx.isOriginLocal());
         } else {
            result = cdl.localNodeIsOwner(key);
         }
      }
      log.tracef("Wrapping entry '%s'? %s", key, result);
      return result;
   }

   @Override
   public Object visitApplyDeltaCommand(InvocationContext ctx, ApplyDeltaCommand command) throws Throwable {
      entryFactory.wrapEntryForDelta(ctx, command.getDeltaAwareKey(), command.getDelta());
      return invokeNextInterceptor(ctx, command);
   }

   @Override
   public final Object visitRemoveCommand(InvocationContext ctx, RemoveCommand command) throws Throwable {
      if (shouldWrap(command.getKey(), ctx, command)) {
         entryFactory.wrapEntryForRemove(ctx, command.getKey());
      }
      return invokeNextAndApplyChanges(ctx, command);
   }

   @Override
   public final Object visitReplaceCommand(InvocationContext ctx, ReplaceCommand command) throws Throwable {
      if (shouldWrap(command.getKey(), ctx, command)) {
         entryFactory.wrapEntryForReplace(ctx, command.getKey());
      }
      return invokeNextAndApplyChanges(ctx, command);
   }

   @Override
   public Object visitPutMapCommand(InvocationContext ctx, PutMapCommand command) throws Throwable {
      for (Object key : command.getMap().keySet()) {
         if (shouldWrap(key, ctx, command)) {
            entryFactory.wrapEntryForPut(ctx, key, null, true, command);
         }
      }
      return invokeNextAndApplyChanges(ctx, command);
   }

   @Override
   public Object visitEvictCommand(InvocationContext ctx, EvictCommand command) throws Throwable {
      return visitRemoveCommand(ctx, command);
   }

   protected boolean isFromStateTransfer(InvocationContext ctx) {
      if (ctx.isInTxScope() && ctx.isOriginLocal()) {
         LocalTransaction localTx = (LocalTransaction) ((TxInvocationContext) ctx).getCacheTransaction();
         if (localTx.isFromStateTransfer()) {
            return true;
         }
      }
      return false;
   }

   protected boolean isFromStateTransfer(FlagAffectedCommand command) {
      return command.hasFlag(Flag.PUT_FOR_STATE_TRANSFER);
   }

   protected final void commitContextEntries(InvocationContext ctx, boolean skipOwnershipCheck, boolean isPutForStateTransfer) {
      if (!isPutForStateTransfer && stateConsumer != null
            && ctx instanceof TxInvocationContext
            && ((TxInvocationContext) ctx).getCacheTransaction().hasModification(ClearCommand.class)) {
         // If we are committing a ClearCommand now then no keys should be written by state transfer from
         // now on until current rebalance ends.
         stateConsumer.stopApplyingState();
      }

      if (ctx instanceof SingleKeyNonTxInvocationContext) {
         SingleKeyNonTxInvocationContext singleKeyCtx = (SingleKeyNonTxInvocationContext) ctx;
         commitEntryIfNeeded(ctx, skipOwnershipCheck, singleKeyCtx.getKey(), singleKeyCtx.getCacheEntry(), isPutForStateTransfer);
      } else {
         Set<Map.Entry<Object, CacheEntry>> entries = ctx.getLookedUpEntries().entrySet();
         Iterator<Map.Entry<Object, CacheEntry>> it = entries.iterator();
         final Log log = getLog();
         while (it.hasNext()) {
            Map.Entry<Object, CacheEntry> e = it.next();
            CacheEntry entry = e.getValue();
            if (!commitEntryIfNeeded(ctx, skipOwnershipCheck, e.getKey(), entry, isPutForStateTransfer)) {
               if (trace) {
                  if (entry == null)
                     log.tracef("Entry for key %s is null : not calling commitUpdate", e.getKey());
                  else
                     log.tracef("Entry for key %s is not changed(%s): not calling commitUpdate", e.getKey(), entry);
               }
            }
         }
      }
   }

   protected void commitContextEntry(CacheEntry entry, InvocationContext ctx, boolean skipOwnershipCheck) {
      cdl.commitEntry(entry, null, skipOwnershipCheck, ctx);
   }

   private Object invokeNextAndApplyChanges(InvocationContext ctx, FlagAffectedCommand command) throws Throwable {
      final Object result = invokeNextInterceptor(ctx, command);
      if (!ctx.isInTxScope())
         commitContextEntries(ctx, command.hasFlag(Flag.SKIP_OWNERSHIP_CHECK), isFromStateTransfer(command));
      log.tracef("The return value is %s", result);
      return result;
   }

   private final class EntryWrappingVisitor extends AbstractVisitor {

      @Override
      public Object visitClearCommand(InvocationContext ctx, ClearCommand command) throws Throwable {
         boolean wrapped = false;
         for (Object key : dataContainer.keySet()) {
            entryFactory.wrapEntryForClear(ctx, key);
            wrapped = true;
         }
         if (wrapped)
            invokeNextInterceptor(ctx, command);
         if (stateConsumer != null && !ctx.isInTxScope()) {
            // If a non-tx ClearCommand was executed successfully we must stop recording updated keys and do not
            // allow any further updates to be written by state transfer from now on until current rebalance ends.
            stateConsumer.stopApplyingState();
         }
         return null;
      }

      @Override
      public Object visitPutMapCommand(InvocationContext ctx, PutMapCommand command) throws Throwable {
         Map<Object, Object> newMap = new HashMap<Object, Object>(4);
         for (Map.Entry<Object, Object> e : command.getMap().entrySet()) {
            Object key = e.getKey();
            if (cdl.localNodeIsOwner(key)) {
               entryFactory.wrapEntryForPut(ctx, key, null, true, command);
               newMap.put(key, e.getValue());
            }
         }
         if (newMap.size() > 0) {
            PutMapCommand clonedCommand = commandFactory.buildPutMapCommand(newMap, command.getLifespanMillis(), command.getMaxIdleTimeMillis(), command.getFlags());
            invokeNextInterceptor(ctx, clonedCommand);
         }
         return null;
      }

      @Override
      public Object visitRemoveCommand(InvocationContext ctx, RemoveCommand command) throws Throwable {
         if (cdl.localNodeIsOwner(command.getKey())) {
            entryFactory.wrapEntryForRemove(ctx, command.getKey());
            invokeNextInterceptor(ctx, command);
         }
         return null;
      }

      @Override
      public Object visitPutKeyValueCommand(InvocationContext ctx, PutKeyValueCommand command) throws Throwable {
         if (cdl.localNodeIsOwner(command.getKey())) {
            entryFactory.wrapEntryForPut(ctx, command.getKey(), null, !command.isPutIfAbsent(), command);
            invokeNextInterceptor(ctx, command);
         }
         return null;
      }

      @Override
      public Object visitApplyDeltaCommand(InvocationContext ctx, ApplyDeltaCommand command) throws Throwable {
         if (cdl.localNodeIsOwner(command.getKey())) {
            entryFactory.wrapEntryForDelta(ctx, command.getDeltaAwareKey(), command.getDelta());
            invokeNextInterceptor(ctx, command);
         }
         return null;
      }

      @Override
      public Object visitReplaceCommand(InvocationContext ctx, ReplaceCommand command) throws Throwable {
         if (cdl.localNodeIsOwner(command.getKey())) {
            entryFactory.wrapEntryForReplace(ctx, command.getKey());
            invokeNextInterceptor(ctx, command);
         }
         return null;
      }
   }

   private boolean commitEntryIfNeeded(InvocationContext ctx, boolean skipOwnershipCheck, Object key, CacheEntry entry, boolean isPutForStateTransfer) {
      if (entry == null) {
         if (key != null && !isPutForStateTransfer && stateConsumer != null) {
            // this key is not yet stored locally
            stateConsumer.addUpdatedKey(key);
         }
         return false;
      }

      if (isPutForStateTransfer && stateConsumer.isKeyUpdated(key)) {
         // This is a state transfer put command on a key that was already modified by other user commands. We need to back off.
         log.tracef("State transfer will not write key/value %s/%s because it was already updated by somebody else", key, entry.getValue());
         entry.rollback();
         return false;
      }

      if (entry.isChanged() || entry.isLoaded()) {
         log.tracef("About to commit entry %s", entry);
         commitContextEntry(entry, ctx, skipOwnershipCheck);

         if (!isPutForStateTransfer && stateConsumer != null) {
            stateConsumer.addUpdatedKey(key);
         }

         return true;
      }
      return false;
   }
}
