// Copyright 2000-2023 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.intellij.java.codeInsight.daemon;

import com.intellij.codeInspection.miscGenerics.RawUseOfParameterizedTypeInspection;
import com.intellij.psi.PsiFile;
import com.intellij.psi.PsiPatternVariable;
import com.intellij.psi.util.PsiTreeUtil;
import com.intellij.testFramework.LightProjectDescriptor;
import com.intellij.testFramework.fixtures.LightJavaCodeInsightFixtureTestCase;
import org.jetbrains.annotations.NotNull;

import java.util.Collection;

public class DeconstructionInferenceTest extends LightJavaCodeInsightFixtureTestCase {
  @Override
  protected @NotNull LightProjectDescriptor getProjectDescriptor() {
    return JAVA_20;
  }
  
  public void testSimple() {
    // Examples from JEP 432
    myFixture.configureByText("Test.java", """
      record Box<T>(T t) {}
            
      class X {
        static void test1(Box<String> bo) {
          if (bo instanceof Box<String>(var s)) {
            System.out.println("String " + s.trim());
          }
        }
        static void test2(Box<String> bo) {
          if (bo instanceof Box(var s)) {
            System.out.println("String " + s.trim());
          }
        }
        static void test3(Box<Box<String>> bo) {
          if (bo instanceof Box<Box<String>>(Box(var s))) {
            System.out.println("String " + s.trim());
          }
        }
        static void test4(Box<Box<String>> bo) {
          if (bo instanceof Box(Box(var s))) {
            System.out.println("String " + s.trim());
          }
        }
      }
      """);
    myFixture.enableInspections(new RawUseOfParameterizedTypeInspection());
    myFixture.checkHighlighting();
  }

  public void testSwitch() {
    myFixture.configureByText("Test.java", """
      interface I<T> {}
      record Box<T>(T t) implements I<T> {}
            
      class X {
        String test(I<String> i) {
          return switch(i) {
            case Box(var s) -> s.trim();
            default -> null;
          };
        }
      }
      """);
    myFixture.enableInspections(new RawUseOfParameterizedTypeInspection());
    myFixture.checkHighlighting();
  }

  public void testSubType() {
    myFixture.configureByText("Test.java", """
      import java.util.function.Supplier;
            
      record Box<T>(T get) implements Supplier<T> {}

      class Test {
         void test(Supplier<CharSequence> cs) {
           if (cs instanceof Box(String s)) {s.trim();}
           if (cs instanceof Box(Number s)) {s.intValue();}
           if (cs instanceof Box(<error descr="Incompatible types. Found: 'java.lang.Integer', required: 'java.lang.CharSequence'">Integer s</error>)) {s.intValue();}
         }
      }
      """);
    myFixture.checkHighlighting();
  }

  public void testInvalid() {
    myFixture.configureByText("Test.java", """
      import java.util.List;
      
      interface I<T> {}
      record Empty<T extends CharSequence>() implements I<T> {}
            
      class X {
        void test(I<Integer> obj) {
          if (<error descr="Inconvertible types; cannot cast 'I<java.lang.Integer>' to 'Empty'">obj instanceof Empty()</error>) {}
        }
      }
      """);
    myFixture.checkHighlighting();
  }

  public void testTwoParams() {
    myFixture.configureByText("Test.java", """
      import java.util.List;
      
      interface I<A, B> {}
      record R<T>(T t) implements I<T, T> {}
            
      class X {
        void test(I<String, String> obj) {
          if (obj instanceof R(var s)) { s.trim(); }
        }
        void test2(I<String, Integer> obj) {
          if (obj instanceof <error descr="Cannot infer pattern type: Incompatible equality constraint: String and Integer">R</error>(var s)) { s.<error descr="Cannot resolve method 'trim' in 'T'">trim</error>(); }
        }
      }
      """);
    myFixture.checkHighlighting();
  }

  public void testIntersection() {
    myFixture.configureByText("Test.java", """
      import java.io.Serializable;
      import java.util.List;
      import java.util.function.Supplier;
            
      record Box<T extends CharSequence>(T get) implements Supplier<T>, Serializable {}
            
      class ClassTest {
        <T extends Serializable & Supplier<String>> void typeArg(T t) {
            // TODO: wrong error; looks like a problem in isUncheckedCast, not inference-related
            if (t instanceof <error descr="'T' cannot be safely cast to 'Box<String>'">Box<String></error>(var v)) {
              System.out.println(v.trim());
            }
            if (t instanceof <error descr="'T' cannot be safely cast to 'Box'">Box</error>(var v)) {
            System.out.println(v.trim());
          }
        }
        <T extends Serializable & Supplier<Integer>> void typeArgWrong(T t) {
          if (t instanceof <error descr="Cannot infer pattern type: no instance(s) of type variable(s) exist so that Integer conforms to CharSequence">Box</error>(var v)) {
            System.out.println(v.<error descr="Cannot resolve method 'intValue' in 'T'">intValue</error>());
            System.out.println(v.chars());
            System.out.println(v.<error descr="Cannot resolve method 'trim' in 'T'">trim</error>());
          }
        }
        void intersection(Object obj) {
          var x = (Serializable & Supplier<String>) obj;
          if (x instanceof Box(var v)) {
            System.out.println(v.trim());
          }
          var y = (Serializable & Supplier<Integer>) obj;
          if (y instanceof <error descr="Cannot infer pattern type: no instance(s) of type variable(s) exist so that Integer conforms to CharSequence">Box</error>(var v)) {
            System.out.println(v.<error descr="Cannot resolve method 'intValue' in 'T'">intValue</error>());
            System.out.println(v.chars());
            System.out.println(v.<error descr="Cannot resolve method 'trim' in 'T'">trim</error>());
          }
        }
      }
      """);
    myFixture.checkHighlighting();
  }
  
  public void testCaptured() {
    myFixture.configureByText("Test.java", """
      import java.io.Serializable;
      import java.util.List;
      import java.util.function.Supplier;
            
      record Box<T extends CharSequence>(T get) implements Supplier<T>, Serializable {}
            
      class ClassTest {
          void test(List<? extends Supplier<String>> s) {
              if (s.get(0) instanceof Box(var x)) {
                  System.out.println(x.trim());
              }
          }
      }
      """);
    myFixture.checkHighlighting();
  }
  

  public void testImplementsGenericInterface() {
    // Example from JLS 18.5.5 
    PsiFile file = myFixture.configureByText("Test.java", """
      import java.util.function.UnaryOperator;
      
      record Mapper<T>(T in, T out) implements UnaryOperator<T> {
          public T apply(T arg) { return in.equals(arg) ? out : null; }
      }
            
      void test(UnaryOperator<? extends CharSequence> op) {
          if (op instanceof Mapper(var in, var out)) {
              boolean shorter = out.length() < in.length();
          }
      }
      """);
    Collection<PsiPatternVariable> variables = PsiTreeUtil.collectElementsOfType(file, PsiPatternVariable.class);
    for (PsiPatternVariable variable : variables) {
      assertEquals("java.lang.CharSequence", variable.getType().getCanonicalText());
    }
  }

  public void testForEach() {
    myFixture.configureByText("Test.java", """
      import java.util.List;
            
      record Box<T>(T t) {}
            
      class X {
        void test(List<Box<String>> list) {
          for(Box(var text) : list){
            int length = text.length();
          }
        }
        public static void test2(List<Box<? extends String>> records) {
            for (Box(var x ) : records) {
                System.out.println(x.isEmpty());
            }
        }
    
        public static void test3(List<Box<? extends Box<? extends Box<? extends String>>>> records) {
            for (Box(Box(Box(var x))) : records) {
                System.out.println(x.isEmpty());
            }
        }
     }
      """);
    myFixture.enableInspections(new RawUseOfParameterizedTypeInspection());
    myFixture.checkHighlighting();
  }
}
