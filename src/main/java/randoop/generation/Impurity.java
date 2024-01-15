package randoop.generation;

import java.lang.reflect.Constructor;
import java.lang.reflect.Executable;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import randoop.operation.CallableOperation;
import randoop.operation.ConstructorCall;
import randoop.sequence.Statement;
import randoop.sequence.Sequence;
import randoop.types.NonParameterizedType;
import randoop.operation.MethodCall;
import randoop.types.PrimitiveType;
import randoop.types.Type;
import randoop.types.TypeTuple;
import randoop.operation.TypedClassOperation;
import randoop.operation.TypedOperation;
import randoop.util.Randomness;
import randoop.util.ListOfLists;

public class Impurity {
    /*
     * TODO:
     * - Improve code readability, there are way too many methods to handle each type. (SOLVED)
     * - The way randoop works seem to be that when a primitive is initialized, it's value is
     *   used for the final assertion in the test. This means that if we fuzz a primitive, we
     *   will have a different value for the final assertion. Re-read the paper to see if this
     *   is a problem. (SOLVED)
     *   - This isn't an issue because our focus is to let the methods use the fuzzed value to
     *     trigger coverage increase. The final assertion is just a sanity check.
     * - Refactor and implement the fuzzing functionality using Java default methods (maybe). (SOLVED)
     * - Consider how to include the java Random object in the test suite. (SOLVED)
     *   - This seems to be the way I should aim for. I need to figure out how to include the fuzzing
     *     logic in the test suite.
     *   - The import statements of a unit test suite is written in randoop.output.JUnitCreator.java
     *   - Nevermind, I can handle the randomness here so the test suite use deterministic values.
     * - Primitives for fuzzing might be extracted from runtime rather than from the sequence. (TODO)
     * - Deal with statements that outputs void: now we aren't getting any lines that fuzz objects. (SOLVED)
     *   Sequences outputted by fuzz and selectInputs show that the object fuzzing line are
     *   actually there, but we aren't seeing it in tests. (SOLVED)
     *   - The issue isn't just about void. The answer is found in AbstractGenerator.java and GenTests.java handle()
     *     method. Line 479 generated a isOutputTest predicate. It uses several predicates to determine if a
     *     sequence is an output test:
     *     - ExcludeTestPredicate (Success)
     *     - ValueSizePredicate (Success)
     *     - RegressionTestPredicate (Success)
     *     - CompilableTestPredicate (Fail)
     *   - Why did CompilableTestPredicate fail? What we know for now is that person fuzzing and non-assigning
     *     statements are not compilable for some reason.
     *   - Getting more information on why the code isn't compilable doesn't seem trivial. It may be that
     *     Impurity doesn't defaultly understand an object outside of its scope, like `person` in this case.
     *   - Thus, we should try to use Java default methods as a place holder for fuzzer.
     *   - For now, we are not going to fuzz non-primitive types.
     * - Remove all code related to Detective when pushing. (TODO)
     * - We aren't fuzzing the inputs used for method calls. (SOLVED)
     *   - We might need to include extra lines to fuzz the inputs.
     *   - Or we need to fuzz every value upon creation.
     *   - However, the final check is using the fuzzed value. So we only worry about the inputs.
     *   - The component manager doesn't have the fuzzed objects. We might need to include them explicitly.
     *   - I think I just found a trick: for primitives in Sequence, set
     *     private transient boolean shouldInlineLiterals from `true` to `false`. (It works, kinda)
     *   - We still need to figure out how to always let the fuzzed inputs be used.
     *     - Actually, most fuzzed input are selected if properly added 1 to variable index. However,
     *       one relatively rare and hard to solve issue comes up: candidate output type != input type.
     *       - When selecting candidates, we obviously want the last statement to output a
     *         variable of the input type that we need. However, randoop doesn't always do that:
     *         - Consider the following code:
     *          java.lang.String str0 = "hi!";
                java.lang.String str1 = randoop.generation.Impurity.fuzzString(str0);
                char char2 = '#';
                char char3 = randoop.generation.Impurity.fuzzCharacter(char2);
                Person person4 = new Person(str1, (double)char3);
                java.lang.String str5 = "";
                java.lang.String str6 = randoop.generation.Impurity.fuzzString(str5);
                person4.setName(str6);
                double double8 = 100.0d;
                double double9 = randoop.generation.Impurity.fuzzDouble(double8);
                person4.setMoney(double9);
                double double11 = person4.getMoney();
    *          - The last statement isn't the Person that we need, although it involves person. It is
    *            still a possible candidate for some reason.
    *          - Check ComponentManager.getSequencesForType() for more information.
    *
    * - I need to set this to false when Impurity is on. (SOLVED)
    * - Think about how to use TypedOperation.createPrimitiveInitialization(Type type, Object value) to
    *   improve readability. (TODO)
    *   - Some sequences are using the exact same method, which that one we know of its value. We can
    *     apply the change to those sequences. But I don't it has high priority.

     */
    private static final double GAUSSIAN_STD = 30;


    // Private constructor to prevent instantiation.
    private Impurity() {}

    public static ImpurityAndNumStatements fuzz(Sequence sequence) {
        FuzzStatementOffset fuzzStatementOffset = new FuzzStatementOffset();

        Type outputType = sequence.getLastVariable().getType();
        boolean isShort = false;  // Fuzzing short has extra nuances, needs special handling.
        if (outputType.runtimeClassIs(short.class)) {
            outputType = PrimitiveType.forClass(int.class);
            isShort = true;
        }

        // TODO: String fuzzing is not supported yet.
        if (outputType.isVoid()
            || outputType.runtimeClassIs(char.class)
            || outputType.runtimeClassIs(boolean.class)
            || outputType.runtimeClassIs(byte.class)) {
            return new ImpurityAndNumStatements(sequence, 0);
        }

        int stringFuzzingStrategyIndex = 0;  // There are several ways to fuzz a string.
                                             // This is the index of the strategy.
        Class<?> outputClass = outputType.getRuntimeClass();
        if (outputClass.isPrimitive()) {
            sequence = getFuzzedSequenceNumber(sequence, outputClass);
        } else if (outputClass == String.class) {
            stringFuzzingStrategyIndex = Randomness.nextRandomInt(4);
            try {
                sequence = getFuzzedSequenceString(sequence, stringFuzzingStrategyIndex, fuzzStatementOffset);
            } catch (Exception e) {
                System.out.println("Exception when fuzzing String using Impurity. "
                        + sequence.toCodeString());
                System.out.println(e.getMessage());
                return new ImpurityAndNumStatements(sequence, 0);
            }
        } else if (outputClass == null) {
            throw new RuntimeException("Output class is null");
        }
        List<Method> methodList;
        try {
            if (outputClass.isPrimitive()) {
                methodList = getNumberFuzzingMethod(outputClass);
            } else {
                methodList = getStringFuzzingMethod(stringFuzzingStrategyIndex);
            }
        } catch (NoSuchMethodException e) {
            throw new RuntimeException("Initialization failed due to missing method", e);
        }

        Sequence output = sequence;
        for (Method method : methodList) {
            output = createSequence(output, method, fuzzStatementOffset);
        }
        // Sequence output = createSequence(sequence, method, fuzzStatementOffset);


        if (isShort) {
            // First, wrap the int value to a Integer object so we can use the shortValue method
            // to get the short value.
            // e.g. java.lang.Integer int4 = java.lang.Integer.valueOf(int3);
            Method wrapPrimitiveInt;
            try {
                wrapPrimitiveInt = Integer.class.getMethod("valueOf", int.class);
            } catch (NoSuchMethodException e) {
                throw new RuntimeException("Initialization failed due to missing method", e);
            }
            output = createSequence(output, wrapPrimitiveInt, fuzzStatementOffset);

            // Get the short value of the wrapper object.
            // e.g. short short5 = int4.shortValue();
            Method shortValue;
            try {
                shortValue = Integer.class.getMethod("shortValue");
            } catch (NoSuchMethodException e) {
                throw new RuntimeException("Initialization failed due to missing method", e);
            }
            output = createSequence(output, shortValue, fuzzStatementOffset);
        }

        return new ImpurityAndNumStatements(output, fuzzStatementOffset.getOffset());
    }


    // TODO: Turn Method into Executable for generalization.
    private static Sequence createSequence(Sequence sequence, Executable executable,
                                           FuzzStatementOffset fuzzStatementOffset) {
        CallableOperation callableOperation;
        Class<?> outputClass;
        if (executable instanceof Method) {
            callableOperation = new MethodCall((Method) executable);
            outputClass = ((Method) executable).getReturnType();
        } else {
            callableOperation = new ConstructorCall((Constructor<?>) executable);
            outputClass = ((Constructor<?>) executable).getDeclaringClass();
        }

        NonParameterizedType declaringType = new NonParameterizedType(executable.getDeclaringClass());

        List<Type> inputTypeList = new ArrayList<>();
        if (!Modifier.isStatic(executable.getModifiers()) && executable instanceof Method) {
            inputTypeList.add(declaringType);
        }

        for (Class<?> clazz : executable.getParameterTypes()) {
            inputTypeList.add(clazz.isPrimitive() ? PrimitiveType.forClass(clazz) : new NonParameterizedType(clazz));
        }
        TypeTuple inputType = new TypeTuple(inputTypeList);

        Type outputType;
        if (outputClass.isPrimitive()) {
            outputType = PrimitiveType.forClass(outputClass);
        } else {
            outputType = new NonParameterizedType(outputClass);
        }
        TypedOperation typedOperation = new TypedClassOperation(callableOperation,
                declaringType, inputType, outputType);
        List<Integer> inputIndex = new ArrayList<>();
        for (int i = 0; i < inputTypeList.size(); i++) {
            // System.out.println("Statement " + (sequence.size() - inputTypeList.size() + i) + ": " + sequence.getStatement(sequence.size() - inputTypeList.size() + i));
            inputIndex.add(sequence.size() - inputTypeList.size() + i);
        }
        fuzzStatementOffset.increment(inputTypeList.size());

        List<Sequence> sequenceList = Collections.singletonList(sequence);

        // System.out.println("typedOperation: " + typedOperation);
        // System.out.println("sequenceList: " + sequenceList);
        // System.out.println("inputIndex: " + inputIndex);

        return Sequence.createSequence(typedOperation, sequenceList, inputIndex);
    }


    // Get a fuzzed sequence given a sequence and the output class
    private static Sequence getFuzzedSequenceNumber(Sequence sequence, Class<?> outputClass) {
        List<Sequence> sequenceList = Collections.singletonList(sequence);

        double randomGaussian = GAUSSIAN_STD * Randomness.nextRandomGaussian(1);
        Object fuzzedValue;
        if (outputClass == int.class) {
            fuzzedValue = (int) Math.round(randomGaussian);
        } else if (outputClass == short.class) {
            // This is a temporary work around to bypass the issue when fuzzing short.
            // Short does not have a sum method, and this introduce challenges to the implementation
            // of short fuzzing as using sum method is the only obvious way to fuzz numbers.
            fuzzedValue = (int) Math.round(randomGaussian);
        } else if (outputClass == long.class) {
            fuzzedValue = Math.round(randomGaussian);
        } else if (outputClass == byte.class) {
            fuzzedValue = (byte) Math.round(randomGaussian);
        } else if (outputClass == float.class) {
            fuzzedValue = (float) randomGaussian;
        } else if (outputClass == double.class) {
            fuzzedValue = randomGaussian;
        } else {
            throw new RuntimeException("Unexpected primitive type: " + outputClass.getName());
        }

        Sequence fuzzingSequence = Sequence.createSequenceForPrimitive(fuzzedValue);
        List<Sequence> fuzzingSequenceList = Collections.singletonList(fuzzingSequence);
        List<Sequence> temp = new ArrayList<>(sequenceList);
        temp.addAll(fuzzingSequenceList);
        sequence = Sequence.concatenate(temp);
        return sequence;
    }


    private static Sequence getFuzzedSequenceString(Sequence sequence, int fuzzingOperationIndex,
                                                    FuzzStatementOffset fuzzStatementOffset)
            throws IllegalArgumentException, IndexOutOfBoundsException {
        // Create a Stringbuilder object
        Constructor<?> stringBuilderConstructor;
        try {
            stringBuilderConstructor = StringBuilder.class.getConstructor(String.class);
        } catch (NoSuchMethodException e) {
            throw new RuntimeException("Initialization failed due to missing method", e);
        }
        sequence = createSequence(sequence, stringBuilderConstructor, fuzzStatementOffset);

        List<Sequence> sequenceList = Collections.singletonList(sequence);
        List<Sequence> fuzzingSequenceList = new ArrayList<>();

        Object stringValue;
        try {
            stringValue = sequence.getStatement(sequence.size() - 2).getValue();
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("This is normal and does not indicate error. " +
                    "It happens when the input String " +
                    "is not obtained from the collection of known String. ");
        }
        int stringLength = stringValue.toString().length();
        if (fuzzingOperationIndex == 0) {
            // Inserting a character
            int randomIndex = (stringLength == 0 ? 0 : Randomness.nextRandomInt(stringLength));
            Sequence randomIndexSequence = Sequence.createSequenceForPrimitive(randomIndex);
            char randomChar = (char) (Randomness.nextRandomInt(95) + 32);  // ASCII 32-126
            Sequence randomCharSequence = Sequence.createSequenceForPrimitive(randomChar);

            fuzzingSequenceList.add(randomIndexSequence);
            fuzzingSequenceList.add(randomCharSequence);
        } else if (fuzzingOperationIndex == 1) {
            // Removing a character
            if (stringLength == 0) {
                throw new IndexOutOfBoundsException("String length is 0. Will ignore this fuzzing operation.");
            }
            int randomIndex = (stringLength == 0 ? 0 : Randomness.nextRandomInt(stringLength));
            Sequence randomIndexSequence = Sequence.createSequenceForPrimitive(randomIndex);

            fuzzingSequenceList.add(randomIndexSequence);
        } else if (fuzzingOperationIndex == 2) {
            // Replacing a character
            if (stringLength == 0) {
                throw new IndexOutOfBoundsException("String length is 0. Will ignore this fuzzing operation.");
            }
            int randomIndex1 = (stringLength == 0 ? 0 : Randomness.nextRandomInt(stringLength));
            int randomIndex2 = (stringLength == 0 ? 0 : Randomness.nextRandomInt(stringLength));
            int startIndex = Math.min(randomIndex1, randomIndex2);
            int endIndex = Math.max(randomIndex1, randomIndex2);
            Sequence startIndexSequence = Sequence.createSequenceForPrimitive(startIndex);
            Sequence endIndexSequence = Sequence.createSequenceForPrimitive(endIndex);
            String randomChar = String.valueOf((char) (Randomness.nextRandomInt(95) + 32));  // ASCII 32-126
            Sequence randomCharSequence = Sequence.createSequenceForPrimitive(randomChar);

            fuzzingSequenceList.add(startIndexSequence);
            fuzzingSequenceList.add(endIndexSequence);
            fuzzingSequenceList.add(randomCharSequence);
        } else if (fuzzingOperationIndex == 3) {
            // Selecting a substring
            if (stringLength == 0) {
                throw new IndexOutOfBoundsException("String length is 0. Will ignore this fuzzing operation.");
            }
            int randomIndex1 = (stringLength == 0 ? 0 : Randomness.nextRandomInt(stringLength));
            int randomIndex2 = (stringLength == 0 ? 0 : Randomness.nextRandomInt(stringLength));
            int startIndex = Math.min(randomIndex1, randomIndex2);
            int endIndex = Math.max(randomIndex1, randomIndex2);
            Sequence startIndexSequence = Sequence.createSequenceForPrimitive(startIndex);
            Sequence endIndexSequence = Sequence.createSequenceForPrimitive(endIndex);

            fuzzingSequenceList.add(startIndexSequence);
            fuzzingSequenceList.add(endIndexSequence);
        } else {
            // This should never happen
            throw new IllegalArgumentException("Invalid fuzzing operation index: " + fuzzingOperationIndex);
        }

        List<Sequence> temp = new ArrayList<>(sequenceList);
        temp.addAll(fuzzingSequenceList);
        sequence = Sequence.concatenate(temp);
        return sequence;
    }


    private static List<Method> getNumberFuzzingMethod(Class<?> outputClass) throws NoSuchMethodException {
        // System.out.println("Output class is: " + outputClass);

        List<Method> methodList = new ArrayList<>();

        // Map each wrapper to its primitive type and a common method
        if (outputClass == int.class) {
            methodList.add(Integer.class.getMethod("sum", int.class, int.class));
        } else if (outputClass == double.class) {
            methodList.add(Double.class.getMethod("sum", double.class, double.class));
        } else if (outputClass == float.class) {
            methodList.add(Float.class.getMethod("sum", float.class, float.class));
        } else if (outputClass == long.class) {
            methodList.add(Long.class.getMethod("sum", long.class, long.class));
        } else if (outputClass == short.class) {
            methodList.add(Integer.class.getMethod("sum", int.class, int.class));
        } else if (outputClass == byte.class) {
            throw new NoSuchMethodException("Byte fuzzing is not supported yet");
        } else {
            throw new NoSuchMethodException("Object fuzzing is not supported yet");
        }

        if (methodList.isEmpty()) {
            throw new NoSuchMethodException("No suitable method found for class " + outputClass.getName());
        }

        return methodList;
    }

    private static List<Method> getStringFuzzingMethod(int stringFuzzingStrategyIndex) throws NoSuchMethodException {
        List<Method> methodList = new ArrayList<>();

        if (stringFuzzingStrategyIndex == 0) {
            methodList.add(StringBuilder.class.getMethod("insert", int.class, char.class));
            methodList.add(StringBuilder.class.getMethod("toString"));
        } else if (stringFuzzingStrategyIndex == 1) {
            methodList.add(StringBuilder.class.getMethod("deleteCharAt", int.class));
            methodList.add(StringBuilder.class.getMethod("toString"));
        } else if (stringFuzzingStrategyIndex == 2) {
            methodList.add(StringBuilder.class.getMethod("replace", int.class, int.class, String.class));
            methodList.add(StringBuilder.class.getMethod("toString"));
        } else if (stringFuzzingStrategyIndex == 3) {
            methodList.add(StringBuilder.class.getMethod("substring", int.class, int.class));
        } else {
            throw new NoSuchMethodException("Object fuzzing is not supported yet");
        }

        if (methodList.isEmpty()) {
            throw new NoSuchMethodException("No suitable method found for class String");
        }

        return methodList;
    }


    private static class FuzzStatementOffset {
        private int offset;

        private FuzzStatementOffset() {
            this.offset = 0;
        }

        private int getOffset() {
            return this.offset;
        }

        private void increment(int numStatements) {
            this.offset += numStatements;
        }
    }
}
