package randoop.reflection;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Set;

import randoop.operation.NonreceiverTerm;
import randoop.operation.TypedOperation;
import randoop.sequence.Sequence;
import randoop.sequence.Variable;
import randoop.types.ClassOrInterfaceType;
import randoop.util.ClassFileConstants;
import randoop.util.MultiMap;

/**
 * {@code ClassLiteralExtractor} is a {@link ClassVisitor} that extracts literals from the bytecode
 * of each class visited, adding a sequence for each to a map associating a sequence with a type.
 *
 * @see OperationModel
 */
class ClassLiteralExtractor extends DefaultClassVisitor {

  private MultiMap<ClassOrInterfaceType, Sequence> literalMap;

  ClassLiteralExtractor(MultiMap<ClassOrInterfaceType, Sequence> literalMap) {
    this.literalMap = literalMap;
  }

  @Override
  public void visitBefore(Class<?> c) {
    ClassOrInterfaceType constantType = ClassOrInterfaceType.forClass(c);
    Set<NonreceiverTerm> nonreceiverTerms = ClassFileConstants.getNonreceiverTerms(c);
    for (NonreceiverTerm term : nonreceiverTerms) {
      Sequence seq =
          new Sequence()
              .extend(
                  TypedOperation.createNonreceiverInitialization(term),
                  new ArrayList<Variable>(0));
      literalMap.add(constantType, seq);
    }
  }
}
