package org.infinispan.stream.impl.intops.object;

import java.util.function.Predicate;
import java.util.stream.Stream;

import org.infinispan.stream.impl.intops.IntermediateOperation;

import io.reactivex.Flowable;

/**
 * Performs filter operation on a regular {@link Stream}
 * @param <S> the type in the stream
 */
public class FilterOperation<S> implements IntermediateOperation<S, Stream<S>, S, Stream<S>> {
   private final Predicate<? super S> predicate;

   public FilterOperation(Predicate<? super S> predicate) {
      this.predicate = predicate;
   }

   @Override
   public Stream<S> perform(Stream<S> stream) {
      return stream.filter(predicate);
   }

   public Predicate<? super S> getPredicate() {
      return predicate;
   }

   @Override
   public Flowable<S> mapFlowable(Flowable<S> input) {
      return input.filter(predicate::test);
   }
}
