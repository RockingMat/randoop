package randoop.generation;

import java.lang.reflect.Constructor;
import java.lang.reflect.Executable;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.Set;
import java.util.stream.Collectors;
import org.checkerframework.checker.nullness.qual.Nullable;
import randoop.DummyVisitor;
import randoop.ExecutionOutcome;
import randoop.NormalExecution;
import randoop.operation.CallableOperation;
import randoop.operation.ConstructorCall;
import randoop.operation.MethodCall;
import randoop.operation.TypedClassOperation;
import randoop.operation.TypedOperation;
import randoop.sequence.ExecutableSequence;
import randoop.sequence.Sequence;
import randoop.test.DummyCheckGenerator;
import randoop.types.NonParameterizedType;
import randoop.types.PrimitiveType;
import randoop.types.Type;
import randoop.types.TypeTuple;
import randoop.util.ListOfLists;
import randoop.util.Randomness;
import randoop.util.SimpleList;

/**
 * Implements the Detective component, as described by the paper "GRT: Program-Analysis-Guided
 * Random Testing" by Ma et. al (appears in ASE 2015):
 * https://people.kth.se/~artho/papers/lei-ase2015.pdf .
 *
 * <p>A demand-driven approach to construct input objects that are missing for all methods under
 * tests (MUTs). Sometimes not all MUTs can be tested, which could be due to reasons such as
 * required object of type is defined in a third-party library, or the object is created by a method
 * that is not accessible.
 */
public class Detective {

  // TODO: Test performance (speed) with and without the secondary object pool.

  /**
   * Performs a demand-driven approach for constructing input objects of a specified type, when the
   * object pool (and secondary object pool) contains no objects of that type.
   *
   * <p>This method identifies a set of methods that return or construct objects of the required
   * type. For each of these methods: it generates a method sequence for the method by recursively
   * searching for necessary inputs from the provided object pools; executes it; and if successful,
   * stores the resultant object in the secondary object pool.
   *
   * <p>Finally, it returns the newly-created sequences (that produce objects of the required type)
   * from the secondary object pool. (We might want to merge the two pools eventually; using
   * different pools reproduces GRT's Detective exactly.)
   *
   * <p>Invariant: This method is only called where the component manager lacks an object that is of
   * a type compatible with the one required by the forward generator. See
   * randoop.generation.ForwardGenerator#selectInputs.
   *
   * @param mainObjPool the main object pool that stores sequences generated by the non-Detective
   *     parts of Randoop
   * @param secondaryObjPool the secondary object pool that stores sequences generated by Detective
   * @param t the class type for which the input objects need to be constructed
   * @return a SimpleList of method sequences that produce objects of the required type
   */
  public static SimpleList<Sequence> demandDrivenInputCreation(
      ObjectPool mainObjPool, ObjectPool secondaryObjPool, Type t) {
    // Extract all constructors/methods that constructs/returns the demanded type by
    // searching through all methods of the main object pool.
    Set<TypedOperation> producerMethodSet = extractProducerMethods(t);

    // For each producer method, create a sequence that produces an object of the demanded type
    // if possible, or produce a sequence that leads to the eventual creation of the demanded type.
    for (TypedOperation producerMethod : producerMethodSet) {
      Sequence newSequence = getInputAndGenSeq(mainObjPool, secondaryObjPool, producerMethod);
      if (newSequence != null) {
        // Execute the sequence and store the resultant object in the secondary object pool
        // if the sequence is successful.
        processSuccessfulSequence(secondaryObjPool, newSequence);
      }
    }

    // Extract all method sequences that produce objects of the demanded type from the secondary
    // object pool and return them.
    return extractCandidateMethodSequences(secondaryObjPool, t);
  }

  /**
   * Identifies a set of methods that construct objects of a specified type.
   *
   * <p>The method checks for all visible methods and constructors in the specified type that return
   * the same type. It also recursively searches for inputs needed to execute a method that returns
   * the type. The recursive search terminates if the current type is a primitive type or if it has
   * already been processed.
   *
   * @param t the type for which the producer methods are to be extracted
   * @return a set of TypedOperations that construct objects of the specified type t
   */
  public static Set<TypedOperation> extractProducerMethods(Type t) {
    Set<Type> processed = new HashSet<>();
    Queue<Type> workList = new ArrayDeque<>();

    // The set of producer methods that construct objects of the specified type.
    Set<TypedOperation> producerMethodSet = new LinkedHashSet<>();

    // The set of types that are needed to construct objects of the specified type.
    Set<Type> producerTypeSet = new HashSet<>();
    workList.add(t);

    // Recursively search for methods that construct objects of the specified type.
    while (!workList.isEmpty()) {
      Type currentType = workList.poll();

      // Only consider the type if it is not a primitive type or if it hasn't already been
      // processed.
      if (!processed.contains(currentType) && !currentType.isNonreceiverType()) {
        Class<?> currentTypeClass = currentType.getRuntimeClass();
        List<Executable> executableList = new ArrayList<>();

        // Adding constructors.
        for (Constructor<?> constructor : currentTypeClass.getConstructors()) {
          executableList.add(constructor);
        }
        // Adding methods.
        for (Method method : currentTypeClass.getMethods()) {
          executableList.add(method);
        }

        for (Executable executable : executableList) {
          if (executable instanceof Constructor
              || (executable instanceof Method
                  && ((Method) executable).getReturnType().equals(currentTypeClass))) {
            // Obtain the input types and output type of the executable.
            Class<?>[] inputClassesArray = executable.getParameterTypes();
            List<Type> inputTypeList = classArrayToTypeList(inputClassesArray);
            // If the executable is a non-static method, add the receiver type to
            // the front of the input type list.
            if (executable instanceof Method && !Modifier.isStatic(executable.getModifiers())) {
              inputTypeList.add(0, new NonParameterizedType(currentTypeClass));
            }
            TypeTuple inputTypes = new TypeTuple(inputTypeList);

            Type outputType = classToType(currentTypeClass);

            CallableOperation callableOperation =
                executable instanceof Constructor
                    ? new ConstructorCall((Constructor<?>) executable)
                    : new MethodCall((Method) executable);

            NonParameterizedType declaringType = new NonParameterizedType(currentTypeClass);
            TypedOperation typedClassOperation =
                new TypedClassOperation(callableOperation, declaringType, inputTypes, outputType);

            // Add the method call to the producerMethodSet.
            producerMethodSet.add(typedClassOperation);
            producerTypeSet.addAll(inputTypeList);
          }
          processed.add(currentType);
          // Recursively search for methods that construct objects of the specified type.
          workList.addAll(producerTypeSet);
        }
      }
    }
    return producerMethodSet;
  }

  /**
   * Given an array of classes, this method converts them into a list of Types.
   *
   * @param classes an array of reflection classes
   * @return a list of Types
   */
  private static List<Type> classArrayToTypeList(Class<?>[] classes) {
    List<Type> inputTypeList = new ArrayList<>();
    for (Class<?> inputClass : classes) {
      Type inputType = classToType(inputClass);
      inputTypeList.add(inputType);
    }
    return inputTypeList;
  }

  /**
   * Given a class, this method converts it into a Type.
   *
   * @param clazz a class
   * @return a Type
   */
  private static Type classToType(Class<?> clazz) {
    if (clazz.isPrimitive()) {
      return PrimitiveType.forClass(clazz);
    } else {
      return new NonParameterizedType(clazz);
    }
  }

  /**
   * Given a TypedOperation, this method finds a sequence of method calls that can generate an
   * instance of each input type required by the TypedOperation. It then merges these sequences into
   * a single sequence.
   *
   * @param mainObjPool the main object pool from which to draw input sequences
   * @param secondaryObjPool the secondary object pool from which to draw input sequences if the
   *     main object pool does not contain a suitable sequence
   * @param typedOperation the operation for which input sequences are to be generated
   * @return a sequence that ends with a call to the provided TypedOperation and contains calls to
   *     generate each required input, or null if no such sequence can be found
   */
  private static @Nullable Sequence getInputAndGenSeq(
      ObjectPool mainObjPool, ObjectPool secondaryObjPool, TypedOperation typedOperation) {
    TypeTuple inputTypes = typedOperation.getInputTypes();
    List<Sequence> inputSequences = new ArrayList<>();
    List<Integer> inputIndices = new ArrayList<>();
    Map<Type, List<Integer>> typeToIndex = new HashMap<>();

    // 'index' tracks the global position of each variable across all sequences, used for mapping
    // variable types to their indices in the final sequence.
    // This is crucial for accurately constructing the final sequence, ensuring that each input is
    // correctly placed for the execution of the 'TypedOperation'.
    int index = 0;

    for (int i = 0; i < inputTypes.size(); i++) {
      // Obtain a sequence that generates an object of the required type from the main object pool.
      ObjectPool typeFilteredPool = mainObjPool.getSubPoolOfType(inputTypes.get(i));
      // If no such sequence exists, obtain a sequence from the secondary object pool.
      if (typeFilteredPool.isEmpty()) {
        typeFilteredPool = secondaryObjPool.getSubPoolOfType(inputTypes.get(i));
        if (typeFilteredPool.isEmpty()) {
          // If no such sequence exists, return null.
          return null;
        }
      }

      // Randomly select an object and get its sequence from the typeFilteredPool.
      Object obj =
          Randomness.randomMember(typeFilteredPool.keySet().stream().collect(Collectors.toList()));
      Sequence seq = typeFilteredPool.get(obj);

      inputSequences.add(seq);

      // For each variable in the sequence, assign an index and map its type to this index.
      for (int j = 0; j < seq.size(); j++) {
        Type type = seq.getVariable(j).getType();
        if (!typeToIndex.containsKey(type)) {
          typeToIndex.put(type, new ArrayList<>());
        }
        typeToIndex.get(type).add(index);
        index++;
      }
    }

    Set<Integer> inputIndicesSet = new LinkedHashSet<>();

    // For each input type of the operation, add its corresponding indices.
    for (Type inputType : inputTypes) {
      if (typeToIndex.containsKey(inputType)) {
        inputIndicesSet.addAll(typeToIndex.get(inputType));
      }
    }

    // Add the indices to the inputIndices list.
    inputIndices.addAll(inputIndicesSet);

    return Sequence.createSequence(typedOperation, inputSequences, inputIndices);
  }

  /**
   * Executes a single sequence and updates the given object pool with the outcome if it's a
   * successful execution. This method is a convenience wrapper for processing individual sequences.
   *
   * @param objectPool the ObjectPool where the outcome, if successful, is stored
   * @param sequence the sequence to be executed
   */
  public static void processSuccessfulSequence(ObjectPool objectPool, Sequence sequence) {
    // Guaranteed to have only one sequence per execution.
    Set<Sequence> setSequence = Collections.singleton(sequence);
    addExecutedSequencesToPool(objectPool, setSequence);
  }

  /**
   * Executes a set of sequences and updates the object pool with each successful execution. It
   * iterates through each sequence, executes it, and if the execution is normal and yields a
   * non-null value, the value along with its generating sequence is added or updated in the object
   * pool.
   *
   * @param objectPool the ObjectPool to be updated with successful execution outcomes
   * @param sequenceSet a set of sequences to be executed
   */
  private static void addExecutedSequencesToPool(ObjectPool objectPool, Set<Sequence> sequenceSet) {
    for (Sequence genSeq : sequenceSet) {
      ExecutableSequence eseq = new ExecutableSequence(genSeq);
      eseq.execute(new DummyVisitor(), new DummyCheckGenerator());

      Object generatedObjectValue = null;
      ExecutionOutcome lastOutcome = eseq.getResult(eseq.sequence.size() - 1);
      if (lastOutcome instanceof NormalExecution) {
        generatedObjectValue = ((NormalExecution) lastOutcome).getRuntimeValue();
      }

      if (generatedObjectValue != null) {
        // Sequences generated and put into objectPool for an object earlier can be overwritten by
        // new sequences, but this is not a problem because:
        //      - If the object's class is visible to Randoop, then Randoop will be able to generate
        //        tests for the class without the help of Detective.
        //      - If the object's class is not visible to Randoop, we are not testing that class.
        //        Thus, having multiple sequences generating the same object that is not visible to
        //        Randoop does not contribute to the coverage of the tests generated by Randoop.
        objectPool.put(generatedObjectValue, genSeq);
      }
    }
  }

  /**
   * Extracts sequences from the object pool that can generate an object of the specified type.
   *
   * @param objectPool the ObjectPool from which sequences are to be extracted
   * @param t the type of object that the sequences should be able to generate
   * @return a ListOfLists containing sequences that can generate an object of the specified type
   */
  public static ListOfLists<Sequence> extractCandidateMethodSequences(
      ObjectPool objectPool, Type t) {
    return objectPool.getSequencesOfType(t);
  }
}
