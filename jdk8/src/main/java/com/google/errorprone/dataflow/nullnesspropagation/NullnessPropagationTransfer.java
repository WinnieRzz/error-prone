/*
 * Copyright 2014 Google Inc. All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.google.errorprone.dataflow.nullnesspropagation;

import static com.google.errorprone.dataflow.nullnesspropagation.NullnessValue.NONNULL;
import static com.google.errorprone.dataflow.nullnesspropagation.NullnessValue.NULLABLE;

import com.sun.source.tree.Tree;
import com.sun.tools.javac.code.Type.JCPrimitiveType;
import com.sun.tools.javac.tree.JCTree.JCIdent;

import org.checkerframework.dataflow.analysis.ConditionalTransferResult;
import org.checkerframework.dataflow.analysis.RegularTransferResult;
import org.checkerframework.dataflow.analysis.TransferFunction;
import org.checkerframework.dataflow.analysis.TransferInput;
import org.checkerframework.dataflow.analysis.TransferResult;
import org.checkerframework.dataflow.cfg.UnderlyingAST;
import org.checkerframework.dataflow.cfg.node.AbstractNodeVisitor;
import org.checkerframework.dataflow.cfg.node.AssignmentNode;
import org.checkerframework.dataflow.cfg.node.EqualToNode;
import org.checkerframework.dataflow.cfg.node.FieldAccessNode;
import org.checkerframework.dataflow.cfg.node.LocalVariableNode;
import org.checkerframework.dataflow.cfg.node.MethodAccessNode;
import org.checkerframework.dataflow.cfg.node.MethodInvocationNode;
import org.checkerframework.dataflow.cfg.node.Node;
import org.checkerframework.dataflow.cfg.node.NotEqualNode;
import org.checkerframework.dataflow.cfg.node.NullLiteralNode;
import org.checkerframework.dataflow.cfg.node.ObjectCreationNode;
import org.checkerframework.dataflow.cfg.node.ValueLiteralNode;

import java.util.List;

/**
 * This nullness propagation analysis takes on a conservative approach in which local variables
 * assume a {@code NullnessValue} of type non-null only when it is provably non-null. If not enough
 * information is present, the value is unknown and the type defaults to
 * {@link NullnessValue#NULLABLE}.
 * 
 * @author deminguyen@google.com (Demi Nguyen)
 */
public class NullnessPropagationTransfer extends AbstractNodeVisitor<
    TransferResult<NullnessValue, NullnessPropagationStore>,
    TransferInput<NullnessValue, NullnessPropagationStore>>
    implements TransferFunction<NullnessValue, NullnessPropagationStore> {
  
  @Override
  public NullnessPropagationStore initialStore(
      UnderlyingAST underlyingAST, List<LocalVariableNode> parameters) {
    return new NullnessPropagationStore();
  }
  
  /**
   * Handles all other nodes that are not of interest
   */
  @Override
  public TransferResult<NullnessValue, NullnessPropagationStore> visitNode(
      Node node, TransferInput<NullnessValue, NullnessPropagationStore> store) {
    return new RegularTransferResult<>(NULLABLE, store.getRegularStore());
  }
  
  // Literals
  /**
   * Note: A short literal appears as an int to the compiler, and the compiler can perform a
   * narrowing conversion on the literal to cast from int to short. For example, when assigning a
   * literal to a short variable, the literal does not transfer its own non-null type to the
   * variable. Instead, the variable receives the non-null type from the return value of the
   * conversion call.
   */
  @Override
  public TransferResult<NullnessValue, NullnessPropagationStore> visitValueLiteral(
      ValueLiteralNode node, TransferInput<NullnessValue, NullnessPropagationStore> before) {
    return update(before, node, NONNULL);
  }

  @Override
  public TransferResult<NullnessValue, NullnessPropagationStore> visitNullLiteral(
      NullLiteralNode node, TransferInput<NullnessValue, NullnessPropagationStore> before) {
    return update(before, node, NULLABLE);
  }
  
  /**
   * Refines the {@code NullnessValue} of a {@code LocalVariableNode} used in comparison. If the lhs
   * and rhs are equal, the local variable(s) receives the most refined type between the two
   * operands. Else, the variable(s) retains its original value. A comparison against {@code null}
   * always transfers either {@link NullnessValue#NULLABLE} or {@link NullnessValue#NONNULL} to the
   * variable type.
   */
  @Override
  public TransferResult<NullnessValue, NullnessPropagationStore> visitEqualTo(
      EqualToNode node, TransferInput<NullnessValue, NullnessPropagationStore> before) {
    NullnessPropagationStore thenStore = before.getRegularStore();
    NullnessPropagationStore elseStore = thenStore.copy();
    
    Node leftNode = node.getLeftOperand();
    Node rightNode = node.getRightOperand();    
   
    NullnessValue leftVal = thenStore.getInformation(leftNode);
    NullnessValue rightVal = thenStore.getInformation(rightNode);
    NullnessValue value = leftVal.greatestLowerBound(rightVal);

    if (leftNode instanceof LocalVariableNode) {
      if (rightNode instanceof NullLiteralNode) {
        thenStore.setInformation(leftNode, NULLABLE);
        elseStore.setInformation(leftNode, NONNULL);
      } else {
        thenStore.setInformation(leftNode, value);
      }
    }
    if (rightNode instanceof LocalVariableNode) {
      if (leftNode instanceof NullLiteralNode) {
        thenStore.setInformation(rightNode, NULLABLE);
        elseStore.setInformation(rightNode, NONNULL);
      } else {
        thenStore.setInformation(rightNode, value);
      }
    }

    return new ConditionalTransferResult<>(NULLABLE, thenStore, elseStore);
  }
  
  /**
   * Refines the {@code NullnessValue} of a {@code LocalVariableNode} used in a comparison. If the
   * lhs and rhs are not equal, the local variable(s) retains its original value. Else, both sides
   * are equal, and the local variable(s) receives the most refined type between the two operands.
   * A comparison against {@code null} always transfers either {@link NullnessValue#NULLABLE} or
   * {@link NullnessValue#NONNULL} to the variable type.
   */
  @Override
  public TransferResult<NullnessValue, NullnessPropagationStore> visitNotEqual(
      NotEqualNode node, TransferInput<NullnessValue, NullnessPropagationStore> before) {
    NullnessPropagationStore thenStore = before.getRegularStore();
    NullnessPropagationStore elseStore = thenStore.copy();
    
    Node leftNode = node.getLeftOperand();
    Node rightNode = node.getRightOperand();
    
    NullnessValue leftVal = thenStore.getInformation(leftNode);
    NullnessValue rightVal = thenStore.getInformation(rightNode);    
    NullnessValue value = leftVal.greatestLowerBound(rightVal);
    
    if (leftNode instanceof LocalVariableNode) {
      if (rightNode instanceof NullLiteralNode) {
        thenStore.setInformation(leftNode, NONNULL);
        elseStore.setInformation(leftNode, NULLABLE);
      }
      elseStore.setInformation(leftNode, value);
    }
    if (rightNode instanceof LocalVariableNode) {
      if (leftNode instanceof NullLiteralNode) {
        thenStore.setInformation(rightNode, NONNULL);
        elseStore.setInformation(rightNode, NULLABLE);
      }
      elseStore.setInformation(rightNode, value);
    }

    return new ConditionalTransferResult<>(NULLABLE, thenStore, elseStore);
  }
  
  /**
   * Transfers the value of the rhs to the local variable on the lhs of an assignment statement.
   */
  @Override
  public TransferResult<NullnessValue, NullnessPropagationStore> visitAssignment(
      AssignmentNode node, TransferInput<NullnessValue, NullnessPropagationStore> before) {
    NullnessPropagationStore after = before.getRegularStore();
    Node target = node.getTarget();
    NullnessValue value = NULLABLE;
    if (target instanceof LocalVariableNode) {
      LocalVariableNode t = (LocalVariableNode) target;
      value = after.getInformation(node.getExpression());
      after.setInformation(t, value);
    }
    return new RegularTransferResult<>(value, after);
  }
  
  /**
   * Variables of primitive type are always refined to non-null.
   */
  @Override
  public TransferResult<NullnessValue, NullnessPropagationStore> visitLocalVariable(
      LocalVariableNode node, TransferInput<NullnessValue, NullnessPropagationStore> before) {
    NullnessValue value = before.getRegularStore().getInformation(node);

    Tree tree = node.getTree();
    if (tree instanceof JCIdent) {
      JCIdent ident = (JCIdent) tree;
      if (ident.type instanceof JCPrimitiveType) {
        value = NONNULL;
      }
    }
    return update(before, node, value);
  }

  /**
   * Refines the receiver of a field access to type non-null after a successful field access.
   * 
   * Note: If the field access occurs when the node is an l-value, the analysis won't
   * call any transfer functions for that node. For example, {@code object.field = var;} would not
   * call {@code visitFieldAccess} for {@code object.field}. On the other hand,
   * {@code var = object.field} would call {@code visitFieldAccess} and make a successful transfer.
   */
  @Override
  public TransferResult<NullnessValue, NullnessPropagationStore> visitFieldAccess(
      FieldAccessNode node, TransferInput<NullnessValue, NullnessPropagationStore> before) {
    Node receiver = node.getReceiver();
    return update(before, receiver, NONNULL);
  }

  /*
   * TODO(cpovirk): Eliminate this, which is redundant with visitMethodInvocation. Every
   * MethodInvocationNode contains a MethodAccessNode, and every MethodAccessNode is contained
   * within a MethodInvocationNode.
   */
  /**
   * Refines the receiver of a method access to type non-null after a successful method access.
   */
  @Override
  public TransferResult<NullnessValue, NullnessPropagationStore> visitMethodAccess(
      MethodAccessNode node, TransferInput<NullnessValue, NullnessPropagationStore> before) {
    Node receiver = node.getReceiver();
    return update(before, receiver, NONNULL);
  }

  /*
   * We don't need to handle narrowing conversions. Narrowing occurs with assignments to types like
   * |short|. But the only legal types to assign to a short are (a) the set of primitives that can
   * be narrowed to short and (b) java.lang.Short. The first group can't be null, and the second
   * doesn't cause a narrowing conversion.
   *
   * Maybe someday we will write a detector that determines whether "short s = ..." can throw a
   * NullPointerException. If we do, we can implement visitNarrowingConversion to return NONNULL.
   */

  /**
   * Refines the receiver of a method invocation to type non-null after successful invocation.
   */
  @Override
  public TransferResult<NullnessValue, NullnessPropagationStore> visitMethodInvocation(
      MethodInvocationNode node, TransferInput<NullnessValue, NullnessPropagationStore> before) {
    Node receiver = node.getTarget().getReceiver();
    return update(before, receiver, NONNULL);
  }
  
  /**
   * The node for object instantiation has a non-null type because {@code new} can never return
   * {@code null}.
   */
  @Override
  public TransferResult<NullnessValue, NullnessPropagationStore> visitObjectCreation(
      ObjectCreationNode node, TransferInput<NullnessValue, NullnessPropagationStore> before) {
    return update(before, node, NONNULL);
  }

  // TODO(cpovirk): handle instanceof

  /** Maps the given node to the specified value in the resulting store from the node visit. */
  private static RegularTransferResult<NullnessValue, NullnessPropagationStore> update(
      TransferInput<?, NullnessPropagationStore> before, Node node, NullnessValue value) {
    NullnessPropagationStore after = before.getRegularStore();
    after.setInformation(node, value);
    // TODO(cpovirk): How is |value| used here? I see that Analysis calls getResultValue(). Hmm.
    return new RegularTransferResult<>(value, after);
  }
}