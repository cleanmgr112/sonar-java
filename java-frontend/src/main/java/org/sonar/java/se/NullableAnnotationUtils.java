/*
 * SonarQube Java
 * Copyright (C) 2012-2020 SonarSource SA
 * mailto:info AT sonarsource DOT com
 *
 * This program is free software; you can redistribute it and/or
 * modify it under the terms of the GNU Lesser General Public
 * License as published by the Free Software Foundation; either
 * version 3 of the License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU
 * Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License
 * along with this program; if not, write to the Free Software Foundation,
 * Inc., 51 Franklin Street, Fifth Floor, Boston, MA  02110-1301, USA.
 */
package org.sonar.java.se;

import com.google.common.collect.ImmutableSet;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Stream;
import javax.annotation.CheckForNull;
import org.sonar.java.model.JUtils;
import org.sonar.plugins.java.api.semantic.Symbol;
import org.sonar.plugins.java.api.semantic.SymbolMetadata;
import org.sonar.plugins.java.api.semantic.Type;
import org.sonar.plugins.java.api.tree.IdentifierTree;
import org.sonar.plugins.java.api.tree.MemberSelectExpressionTree;
import org.sonar.plugins.java.api.tree.NewArrayTree;
import org.sonar.plugins.java.api.tree.Tree;

public final class NullableAnnotationUtils {

  private NullableAnnotationUtils() {
  }

  private static final String JAVAX_ANNOTATION_PARAMETERS_ARE_NONNULL_BY_DEFAULT = "javax.annotation.ParametersAreNonnullByDefault";
  private static final String ORG_ECLIPSE_JDT_ANNOTATION_NON_NULL_BY_DEFAULT = "org.eclipse.jdt.annotation.NonNullByDefault";
  private static final String ORG_SPRINGFRAMEWORK_LANG_NON_NULL_API = "org.springframework.lang.NonNullApi";
  private static final String ORG_SPRINGFRAMEWORK_LANG_NON_NULL_FIELDS = "org.springframework.lang.NonNullFields";

  /**
   * Nullable annotations can be "strong", when one must check for nullness, or "weak", when it
   * can be null, but it may be fine to not check it.
   */
  private static final Set<String> STRONG_NULLABLE_ANNOTATIONS = ImmutableSet.of(
    "javax.annotation.CheckForNull",
    // Despite the name, Spring Nullable is meant to be used as CheckForNull
    "org.springframework.lang.Nullable");

  private static final Set<String> NULLABLE_ANNOTATIONS = new ImmutableSet.Builder<String>()
    .add("android.support.annotation.Nullable")
    .add("androidx.annotation.Nullable")
    .add("edu.umd.cs.findbugs.annotations.Nullable")
    .add("javax.annotation.Nullable")
    .add("org.eclipse.jdt.annotation.Nullable")
    .add("org.jetbrains.annotations.Nullable")
    .add("org.checkerframework.checker.nullness.qual.Nullable")
    .add("org.checkerframework.checker.nullness.compatqual.NullableDecl")
    .addAll(STRONG_NULLABLE_ANNOTATIONS)
    .build();

  private static final Set<String> NONNULL_ANNOTATIONS = ImmutableSet.of(
    "android.support.annotation.NonNull",
    "androidx.annotation.NonNull",
    "edu.umd.cs.findbugs.annotations.NonNull",
    "javax.annotation.Nonnull",
    "javax.validation.constraints.NotNull",
    "lombok.NonNull",
    "org.eclipse.jdt.annotation.NonNull",
    "org.jetbrains.annotations.NotNull",
    "org.springframework.lang.NonNull",
    "org.checkerframework.checker.nullness.qual.NonNull",
    "org.checkerframework.checker.nullness.compatqual.NonNullDecl");

  public static boolean isAnnotatedNullable(SymbolMetadata metadata) {
    return isUsingNullable(metadata) || collectMetaAnnotations(metadata).stream().map(Symbol::metadata).anyMatch(NullableAnnotationUtils::isUsingNullable);
  }

  private static boolean isUsingNullable(SymbolMetadata metadata) {
    return NULLABLE_ANNOTATIONS.stream().anyMatch(metadata::isAnnotatedWith) || isNullableThroughNonNull(metadata);
  }

  private static boolean isNullableThroughNonNull(SymbolMetadata metadata) {
    List<SymbolMetadata.AnnotationValue> valuesForAnnotation = metadata.valuesForAnnotation("javax.annotation.Nonnull");
    if (valuesForAnnotation == null || valuesForAnnotation.isEmpty()) {
      return false;
    }
    return checkAnnotationParameter(valuesForAnnotation, "when", "MAYBE") || checkAnnotationParameter(valuesForAnnotation, "when", "UNKNOWN");
  }

  private static boolean checkAnnotationParameter(List<SymbolMetadata.AnnotationValue> valuesForAnnotation, String fieldName, String expectedValue) {
    return valuesForAnnotation.stream()
      .filter(annotationValue -> fieldName.equals(annotationValue.name()))
      .anyMatch(annotationValue -> isExpectedValue(annotationValue.value(), expectedValue));
  }

  private static boolean isExpectedValue(Object annotationValue, String expectedValue) {
    if (annotationValue instanceof Tree) {
      // from sources
      return containsValue((Tree) annotationValue, expectedValue);
    }
    // from binaries
    if (annotationValue instanceof Object[]) {
      return containsValue((Object[]) annotationValue, expectedValue);
    }
    return expectedValue.equals(((Symbol) annotationValue).name());
  }

  private static boolean containsValue(Tree annotationValue, String expectedValue) {
    Symbol symbol;
    switch (annotationValue.kind()) {
      case IDENTIFIER:
        symbol = ((IdentifierTree) annotationValue).symbol();
        break;
      case MEMBER_SELECT:
        symbol = ((MemberSelectExpressionTree) annotationValue).identifier().symbol();
        break;
      case NEW_ARRAY:
        return ((NewArrayTree) annotationValue).initializers().stream().anyMatch(expr -> containsValue(expr, expectedValue));
      default:
        throw new IllegalArgumentException("Unexpected tree used to parameterize annotation");
    }
    return expectedValue.equals(symbol.name());
  }

  private static boolean containsValue(Object[] annotationValue, String expectedValue) {
    return Arrays.stream(annotationValue).map(Symbol.class::cast).anyMatch(symbol -> expectedValue.equals(symbol.name()));
  }

  public static boolean isAnnotatedNonNull(Symbol symbol) {
    if (isAnnotatedNullable(symbol.metadata())) {
      return false;
    }
    return isUsingNonNull(symbol) || collectMetaAnnotations(symbol.metadata()).stream().anyMatch(NullableAnnotationUtils::isUsingNonNull);
  }

  private static boolean isUsingNonNull(Symbol symbol) {
    if (isNullableThroughNonNull(symbol.metadata())) {
      return false;
    }
    SymbolMetadata metadata = symbol.metadata();
    return NONNULL_ANNOTATIONS.stream().anyMatch(metadata::isAnnotatedWith)
      || nonNullReturnTypeAnnotation(symbol) != null
      || nonNullFieldAnnotation(symbol) != null;
  }

  @CheckForNull
  private static String nonNullFieldAnnotation(Symbol symbol) {
    if (symbol.isVariableSymbol() && symbol.owner().isTypeSymbol() && !isUsingNullable(symbol.metadata())
      && valuesForGlobalAnnotation(symbol, ORG_SPRINGFRAMEWORK_LANG_NON_NULL_FIELDS) != null) {
      return ORG_SPRINGFRAMEWORK_LANG_NON_NULL_FIELDS;
    }
    return null;
  }

  @CheckForNull
  private static String nonNullReturnTypeAnnotation(Symbol symbol) {
    if (symbol.isMethodSymbol() && !isUsingNullable(symbol.metadata())) {
      Symbol.MethodSymbol methodSymbol = (Symbol.MethodSymbol) symbol;
      if (isGloballyAnnotatedWithEclipseNonNullByDefault(methodSymbol, "RETURN_TYPE")) {
        return ORG_ECLIPSE_JDT_ANNOTATION_NON_NULL_BY_DEFAULT;
      } else if (valuesForGlobalAnnotation(methodSymbol, ORG_SPRINGFRAMEWORK_LANG_NON_NULL_API) != null) {
        return ORG_SPRINGFRAMEWORK_LANG_NON_NULL_API;
      }
    }
    return null;
  }

  @CheckForNull
  public static String nonNullAnnotation(SymbolMetadata metadata) {
    if (isAnnotatedNullable(metadata)) {
      return null;
    }
    return NONNULL_ANNOTATIONS.stream().filter(metadata::isAnnotatedWith).findFirst().orElse(null);
  }

  @CheckForNull
  public static String nonNullAnnotation(Symbol symbol) {
    SymbolMetadata metadata = symbol.metadata();
    if (isAnnotatedNullable(symbol.metadata())) {
      return null;
    }
    Optional<String> result = NONNULL_ANNOTATIONS.stream().filter(metadata::isAnnotatedWith).findFirst();
    if (result.isPresent()) {
      return result.get();
    }
    String nonNullReturnAnnotation = nonNullReturnTypeAnnotation(symbol);
    if (nonNullReturnAnnotation != null) {
      return nonNullReturnAnnotation;
    }
    return nonNullFieldAnnotation(symbol);
  }

  public static boolean isGloballyAnnotatedParameterNullable(Symbol.MethodSymbol method) {
    return valuesForGlobalAnnotation(method, "javax.annotation.ParametersAreNullableByDefault") != null;
  }

  public static boolean isGloballyAnnotatedParameterNonNull(Symbol.MethodSymbol method) {
    return nonNullAnnotationOnParameters(method) != null;
  }

  @CheckForNull
  public static String nonNullAnnotationOnParameters(Symbol.MethodSymbol method) {
    if (valuesForGlobalAnnotation(method, JAVAX_ANNOTATION_PARAMETERS_ARE_NONNULL_BY_DEFAULT) != null) {
      return JAVAX_ANNOTATION_PARAMETERS_ARE_NONNULL_BY_DEFAULT;
    } else if (valuesForGlobalAnnotation(method, ORG_SPRINGFRAMEWORK_LANG_NON_NULL_API) != null) {
      return ORG_SPRINGFRAMEWORK_LANG_NON_NULL_API;
    } else if (isGloballyAnnotatedWithEclipseNonNullByDefault(method, "PARAMETER")) {
      return ORG_ECLIPSE_JDT_ANNOTATION_NON_NULL_BY_DEFAULT;
    }
    return null;
  }

  @CheckForNull
  private static List<SymbolMetadata.AnnotationValue> valuesForGlobalAnnotation(Symbol symbol, String annotation) {
    return Stream.of(symbol, symbol.enclosingClass(), JUtils.getPackage(symbol))
      .map(s -> s.metadata().valuesForAnnotation(annotation))
      .filter(Objects::nonNull)
      .findFirst()
      .orElse(null);
  }

  private static boolean isGloballyAnnotatedWithEclipseNonNullByDefault(Symbol.MethodSymbol symbol, String parameter) {
    List<SymbolMetadata.AnnotationValue> valuesForGlobalAnnotation = valuesForGlobalAnnotation(symbol, ORG_ECLIPSE_JDT_ANNOTATION_NON_NULL_BY_DEFAULT);
    if (valuesForGlobalAnnotation == null) {
      return false;
    }
    return valuesForGlobalAnnotation.isEmpty() || checkAnnotationParameter(valuesForGlobalAnnotation, "value", parameter);
  }

  private static ArrayList<Symbol> collectMetaAnnotations(SymbolMetadata metadata) {
    return collectMetaAnnotations(metadata, new HashSet<>());
  }

  private static ArrayList<Symbol> collectMetaAnnotations(SymbolMetadata metadata, Set<Type> knownTypes) {
    List<Symbol> result = new ArrayList<>();
    for (SymbolMetadata.AnnotationInstance annotationInstance : metadata.annotations()) {
      Symbol annotationSymbol = annotationInstance.symbol();
      Type annotationType = annotationSymbol.type();
      if (!knownTypes.contains(annotationType)) {
        knownTypes.add(annotationType);
        result.add(annotationSymbol);
        result.addAll(
          collectMetaAnnotations(annotationSymbol.metadata(), knownTypes)
        );
      }
    }
    return new ArrayList<>(result);
  }

  public static boolean isAnnotatedWithStrongNullness(SymbolMetadata metadata) {
    return STRONG_NULLABLE_ANNOTATIONS.stream().anyMatch(metadata::isAnnotatedWith);
  }

}
