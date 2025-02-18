// Copyright 2000-2018 JetBrains s.r.o. Use of this source code is governed by the Apache 2.0 license that can be found in the LICENSE file.
package com.jetbrains.python.documentation;

import com.intellij.lang.documentation.DocumentationProvider;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.projectRoots.Sdk;
import com.intellij.openapi.util.NlsSafe;
import com.intellij.openapi.util.text.HtmlBuilder;
import com.intellij.openapi.util.text.HtmlChunk;
import com.intellij.openapi.util.text.StringUtil;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.psi.*;
import com.intellij.psi.tree.IElementType;
import com.intellij.psi.util.PsiTreeUtil;
import com.intellij.psi.util.QualifiedName;
import com.intellij.util.ObjectUtils;
import com.jetbrains.python.*;
import com.jetbrains.python.codeInsight.controlflow.ScopeOwner;
import com.jetbrains.python.codeInsight.dataflow.scope.ScopeUtil;
import com.jetbrains.python.codeInsight.typing.PyTypingTypeProvider;
import com.jetbrains.python.documentation.docstrings.DocStringUtil;
import com.jetbrains.python.psi.*;
import com.jetbrains.python.psi.impl.ParamHelper;
import com.jetbrains.python.psi.impl.PyBuiltinCache;
import com.jetbrains.python.psi.impl.PyClassImpl;
import com.jetbrains.python.psi.resolve.PyResolveContext;
import com.jetbrains.python.psi.resolve.QualifiedNameFinder;
import com.jetbrains.python.psi.types.*;
import one.util.streamex.StreamEx;
import org.jetbrains.annotations.Nls;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.function.Function;

import static com.jetbrains.python.documentation.DocumentationBuilderKit.TO_ONE_LINE_AND_ESCAPE;
import static com.jetbrains.python.psi.PyUtil.as;

/**
 * Provides quick docs for classes, methods, and functions.
 * Generates documentation stub
 */
public class PythonDocumentationProvider implements DocumentationProvider {
  public static final String DOCUMENTATION_CONFIGURABLE_ID = "com.jetbrains.python.documentation.PythonDocumentationConfigurable";

  private static final int RETURN_TYPE_WRAPPING_THRESHOLD = 80;

  // provides ctrl+hover info
  @Override
  @Nullable
  public @Nls String getQuickNavigateInfo(PsiElement element, @NotNull PsiElement originalElement) {
    for (PythonDocumentationQuickInfoProvider point : PythonDocumentationQuickInfoProvider.EP_NAME.getExtensions()) {
      final String info = point.getQuickInfo(originalElement);
      if (info != null) {
        return info;
      }
    }

    final TypeEvalContext context = TypeEvalContext.userInitiated(originalElement.getProject(), originalElement.getContainingFile());

    if (element instanceof PyFunction) {
      final PyFunction function = (PyFunction)element;
      final HtmlBuilder result = new HtmlBuilder();

      final PyClass cls = function.getContainingClass();
      if (cls != null) {
        final String clsName = cls.getName();
        if (clsName != null) {
          result.appendRaw(PyPsiBundle.message("QDOC.class.name", clsName)).appendRaw("\n");
          // It would be nice to have class import info here, but we don't know the ctrl+hovered reference and context
        }
      }

      result
        .append(describeDecorators(function, Function.identity(), TO_ONE_LINE_AND_ESCAPE, ", ", "\n"))
        .append(describeFunction(function, context, true));

      final String docStringSummary = getDocStringSummary(function);
      if (docStringSummary != null) {
        result.appendRaw("\n").append(docStringSummary);
      }

      return result.toString();
    }
    else if (element instanceof PyClass) {
      final PyClass cls = (PyClass)element;
      final HtmlBuilder result = new HtmlBuilder();

      result
        .append(describeDecorators(cls, Function.identity(), TO_ONE_LINE_AND_ESCAPE, ", ", "\n"))
        .append(describeClass(cls, Function.identity(), TO_ONE_LINE_AND_ESCAPE, false, context));

      final String docStringSummary = getDocStringSummary(cls);
      if (docStringSummary != null) {
        result.appendRaw("\n").append(docStringSummary);
      }
      else {
        Optional
          .ofNullable(cls.findInitOrNew(false, context))
          .map(PythonDocumentationProvider::getDocStringSummary)
          .ifPresent((@NlsSafe var summary) -> result.appendRaw("\n").append(summary));
      }

      return result.toString();
    }
    else if (element instanceof PyExpression) {
      return describeExpression((PyExpression)element, originalElement, context);
    }
    return null;
  }

  @NlsSafe
  @Nullable
  private static String getDocStringSummary(@NotNull PyDocStringOwner owner) {
    final PyStringLiteralExpression docStringExpression = PyDocumentationBuilder.getEffectiveDocStringExpression(owner);
    if (docStringExpression != null) {
      final StructuredDocString docString = DocStringUtil.parse(docStringExpression.getStringValue(), docStringExpression);
      return docString.getSummary();
    }
    return null;
  }

  @NotNull
  static HtmlChunk describeFunction(@NotNull PyFunction function,
                                    @NotNull TypeEvalContext context,
                                    boolean forTooltip) {
    return HtmlChunk.raw(describeFunctionWithTypes(function, context, forTooltip));
  }

  @NotNull
  static HtmlChunk describeTarget(@NotNull PyTargetExpression target, @NotNull TypeEvalContext context) {
    final HtmlBuilder result = new HtmlBuilder();
    result.append(StringUtil.notNullize(target.getName()));
    result.appendRaw(": ");
    describeTypeWithLinks(context.getType(target), target, context, target, result);
    // Can return not physical elements such as foo()[0] for assignments like x, _ = foo()
    final PyExpression value = target.findAssignedValue();
    if (value != null) {
      result.appendRaw(" = ");
      final String initializerText = value.getText();
      final int index = initializerText.indexOf("\n");
      if (index < 0) {
        result.append(initializerText);
      }
      else {
        result.append(initializerText.substring(0, index)).appendRaw("...");
      }
    }
    return result.toFragment();
  }

  @NotNull
  static HtmlChunk describeParameter(@NotNull PyNamedParameter parameter, @NotNull TypeEvalContext context) {
    final HtmlBuilder result = new HtmlBuilder();
    result
      .append(StringUtil.notNullize(parameter.getName()))
      .appendRaw(": ");
    describeTypeWithLinks(context.getType(parameter), parameter, context, parameter, result);
    return result.toFragment();
  }

  @NlsSafe
  @NotNull
  private static String describeFunctionWithTypes(@NotNull PyFunction function,
                                                  @NotNull TypeEvalContext context,
                                                  boolean forTooltip) {
    final StringBuilder result = new StringBuilder();
    // TODO wrapping of long signatures
    if (function.isAsync()) {
      result.append("async ");
    }
    result.append("def ");
    final String funcName = StringUtil.notNullize(function.getName(), PyNames.UNNAMED_ELEMENT);
    int firstParamOffset = result.length() + funcName.length();
    int lastLineOffset = 0;
    if (forTooltip) {
      result.append(escaped(funcName));
    }
    else {
      appendWithTags(result, escaped(funcName), "b");
    }

    result.append("(");
    firstParamOffset++;

    boolean first = true;
    boolean firstIsSelf = false;
    final List<PyCallableParameter> parameters = function.getParameters(context);
    for (PyCallableParameter parameter : parameters) {
      if (!first) {
        result.append(",");
        if (forTooltip || firstIsSelf && parameters.size() == 2) {
          result.append(" ");
        }
        else {
          result.append("\n");
          lastLineOffset = result.length();
          // alignment
          StringUtil.repeatSymbol(result, ' ', firstParamOffset);
        }
      }
      else {
        firstIsSelf = parameter.isSelf();
      }

      String paramName = parameter.getName();
      PyType paramType = parameter.getType(context);
      final PyNamedParameter named = as(parameter.getParameter(), PyNamedParameter.class);
      boolean showType = true;
      if (parameter.isPositionalContainer()) {
        paramName = "*" + StringUtil.notNullize(paramName, "args");
        final PyTupleType tupleType = as(paramType, PyTupleType.class);
        if (tupleType != null) {
          paramType = tupleType.getIteratedItemType();
        }
      }
      else if (parameter.isKeywordContainer()) {
        paramName = "**" + StringUtil.notNullize(paramName, "kwargs");
        final PyCollectionType genericType = as(paramType, PyCollectionType.class);
        if (genericType != null && genericType.getPyClass() == PyBuiltinCache.getInstance(function).getClass("dict")) {
          final List<PyType> typeParams = genericType.getElementTypes();
          paramType = typeParams.size() == 2 ? typeParams.get(1) : null;
        }
      }
      else if (parameter.getParameter() instanceof PySlashParameter) {
        paramName = PySlashParameter.TEXT;
        showType = false;
      }
      else if (parameter.getParameter() instanceof PySingleStarParameter) {
        paramName = PySingleStarParameter.TEXT;
        showType = false;
      }
      else {
        paramName = StringUtil.notNullize(paramName, PyNames.UNNAMED_ELEMENT);
        // Don't show type for "self" unless it's explicitly annotated
        showType = !parameter.isSelf() || (named != null && new PyTypingTypeProvider().getParameterType(named, function, context) != null);
      }
      result.append(escaped(paramName));
      if (showType) {
        result.append(": ");
        result.append(formatTypeWithLinks(paramType, named, function, context));
      }
      final String defaultValue = parameter.getDefaultValueText();
      result.append(escaped(ObjectUtils.notNull(ParamHelper.getDefaultValuePartInSignature(defaultValue, showType), "")));
      first = false;
    }

    result.append(")");

    if (!forTooltip && StringUtil.stripHtml(result.substring(lastLineOffset), false).length() > RETURN_TYPE_WRAPPING_THRESHOLD) {
      result.append("\n ");
    }
    result.append(escaped(" -> "))
          .append(formatTypeWithLinks(context.getReturnType(function), function, function, context));
    return result.toString();
  }

  @NlsSafe
  @Nullable
  private static String describeExpression(@NotNull PyExpression expression,
                                           @NotNull PsiElement originalElement,
                                           @NotNull TypeEvalContext context) {
    final String name = expression.getName();
    if (name != null) {
      final StringBuilder result = new StringBuilder(expression instanceof PyNamedParameter ? "parameter" : "variable");
      result.append(String.format(" \"%s\"", name));

      if (expression instanceof PyNamedParameter) {
        final PyFunction function = PsiTreeUtil.getParentOfType(expression, PyFunction.class);
        if (function != null) {
          result
            .append(" of ")
            .append(function.getContainingClass() == null ? "function" : "method")
            .append(String.format(" \"%s\"", function.getName()));
        }
      }

      if (originalElement instanceof PyTypedElement) {
        final String typeName = getTypeName(context.getType(((PyTypedElement)originalElement)), context);
        result
          .append("\n")
          .append(String.format("Inferred type: %s", typeName));
      }

      return DocumentationBuilderKit.ESCAPE_ONLY.apply(result.toString());
    }
    return null;
  }

  /**
   * @param type    type which name will be calculated
   * @param context type evaluation context
   * @return string representation of the type
   */
  @NotNull
  @NlsSafe
  public static String getTypeName(@Nullable PyType type, @NotNull TypeEvalContext context) {
    return buildTypeModel(type, context).asString();
  }

  /**
   * Returns the provided type in PEP 484 compliant format.
   */
  @NotNull
  public static String getTypeHint(@Nullable PyType type, @NotNull TypeEvalContext context) {
    return buildTypeModel(type, context).asPep484TypeHint();
  }

  /**
   * @param type      type which description will be calculated.
   *                  Description is the same as {@link PythonDocumentationProvider#getTypeDescription(PyType, TypeEvalContext)} gives but
   *                  types are converted to links.
   * @param typeOwner element that has the given type, can be {@code null} for synthetic parameters
   * @param context   type evaluation context
   * @param anchor    anchor element
   * @param body      body to be used to append description
   */
  public static void describeTypeWithLinks(@Nullable PyType type,
                                           @Nullable PyTypedElement typeOwner,
                                           @NotNull TypeEvalContext context,
                                           @NotNull PsiElement anchor,
                                           @NotNull HtmlBuilder body) {
    // Variable annotated with "typing.TypeAlias" marker is deliberately treated as having "Any" type
    if (typeOwner instanceof PyTargetExpression && type == null) {
      PyAssignmentStatement assignment = as(typeOwner.getParent(), PyAssignmentStatement.class);
      if (assignment != null && PyTypingTypeProvider.isExplicitTypeAlias(assignment, context)) {
        body.append("TypeAlias");
        return;
      }
    }
    buildTypeModel(type, context).toBodyWithLinks(body, anchor);
  }

  /**
   * @param type    type which description will be calculated
   * @param context type evaluation context
   * @return more user-friendly description than result of {@link PythonDocumentationProvider#getTypeName(PyType, TypeEvalContext)}.
   * {@code Any} is excluded from {@code Union[Any, ...]}-like types.
   */
  @NotNull
  public static String getTypeDescription(@Nullable PyType type, @NotNull TypeEvalContext context) {
    return buildTypeModel(type, context).asDescription();
  }

  @NotNull
  private static PyTypeModelBuilder.TypeModel buildTypeModel(@Nullable PyType type, @NotNull TypeEvalContext context) {
    return new PyTypeModelBuilder(context).build(type, true);
  }

  @NotNull
  static HtmlChunk describeDecorators(@NotNull PyDecoratable decoratable,
                                        @NotNull Function<String, String> escapedCalleeMapper,
                                        @NotNull Function<@NotNull String, @NotNull String> escaper,
                                        @NotNull @NlsSafe String separator,
                                        @NotNull @NlsSafe String suffix) {
    final HtmlBuilder result = new HtmlBuilder();

    final PyDecoratorList decoratorList = decoratable.getDecoratorList();
    if (decoratorList != null) {
      boolean first = true;

      for (PyDecorator decorator : decoratorList.getDecorators()) {
        if (!first) {
          result.appendRaw(separator);
        }
        result.append(describeDecorator(decorator, escapedCalleeMapper, escaper));
        first = false;
      }
    }

    if (!result.isEmpty()) {
      result.appendRaw(suffix);
    }

    return result.toFragment();
  }

  @NotNull
  static HtmlChunk describeClass(@NotNull PyClass cls,
                                   @NotNull Function<? super String, String> escapedNameMapper,
                                   @NotNull Function<? super @NotNull String, @NlsSafe @NotNull String> escaper,
                                   boolean linkAncestors,
                                   @NotNull TypeEvalContext context) {
    final HtmlBuilder result = new HtmlBuilder();

    @NlsSafe final String name = escapedNameMapper.apply(escaper.apply(StringUtil.notNullize(cls.getName(), PyNames.UNNAMED_ELEMENT)));
    result.appendRaw(escaper.apply("class "));
    result.appendRaw(name);

    final PyExpression[] superClasses = cls.getSuperClassExpressions();
    if (superClasses.length > 0) {
      result.appendRaw(escaper.apply("("));
      boolean isNotFirst = false;

      for (PyExpression superClass : superClasses) {
        if (isNotFirst) {
          result.appendRaw(escaper.apply(", "));
        }
        else {
          isNotFirst = true;
        }

        result.appendRaw(describeSuperClass(superClass, escaper, linkAncestors, context));
      }

      result.appendRaw(escaper.apply(")"));
    }

    return result.toFragment();
  }

  @NlsSafe
  @NotNull
  private static String describeSuperClass(@NotNull PyExpression expression,
                                           @NotNull Function<? super String, String> escaper,
                                           boolean link,
                                           @NotNull TypeEvalContext context) {
    if (link) {
      if (expression instanceof PyReferenceExpression) {
        final PyReferenceExpression referenceExpression = (PyReferenceExpression)expression;
        if (!referenceExpression.isQualified()) {
          final PyResolveContext resolveContext = PyResolveContext.defaultContext(context);

          for (ResolveResult result : referenceExpression.getReference(resolveContext).multiResolve(false)) {
            final PsiElement element = result.getElement();
            if (element instanceof PyClass) {
              final String qualifiedName = ((PyClass)element).getQualifiedName();
              if (qualifiedName != null) {
                return PyDocumentationLink.toPossibleClass(escaper.apply(expression.getText()), qualifiedName, element, context);
              }
            }
          }
        }
      }
      else if (expression instanceof PySubscriptionExpression) {
        final PyExpression operand = ((PySubscriptionExpression)expression).getOperand();
        final PyExpression indexExpression = ((PySubscriptionExpression)expression).getIndexExpression();

        if (indexExpression != null) {
          return describeSuperClass(operand, escaper, true, context) +
                 escaper.apply("[") +
                 describeSuperClass(indexExpression, escaper, true, context) +
                 escaper.apply("]");
        }
      }
      else if (expression instanceof PyKeywordArgument) {
        final String keyword = ((PyKeywordArgument)expression).getKeyword();
        final PyExpression valueExpression = ((PyKeywordArgument)expression).getValueExpression();

        if (PyNames.METACLASS.equals(keyword) && valueExpression != null) {
          return escaper.apply(PyNames.METACLASS + "=") + describeSuperClass(valueExpression, escaper, true, context);
        }
      }
      else if (PyClassImpl.isSixWithMetaclassCall(expression)) {
        final PyCallExpression callExpression = (PyCallExpression)expression;
        final PyExpression callee = callExpression.getCallee();

        if (callee != null) {
          return StreamEx
            .of(callExpression.getArguments())
            .map(argument -> describeSuperClass(argument, escaper, true, context))
            .joining(escaper.apply(", "), escaper.apply(callee.getText() + "("), escaper.apply(")"));
        }
      }
    }

    return escaper.apply(expression.getText());
  }

  @NotNull
  private static HtmlChunk describeDecorator(@NotNull PyDecorator decorator,
                                               @NotNull Function<String, @NlsSafe String> escapedCalleeMapper,
                                               @NotNull Function<@NotNull String, @NotNull @NlsSafe String> escaper) {
    final HtmlBuilder result = new HtmlBuilder();
    result.appendRaw(escaper.apply("@"))
          .appendRaw(escapedCalleeMapper.apply(escaper.apply(PyUtil.getReadableRepr(decorator.getCallee(), false))));

    final PyArgumentList argumentList = decorator.getArgumentList();
    if (argumentList != null) {
      result.appendRaw(escaper.apply(PyUtil.getReadableRepr(argumentList, false)));
    }

    return result.toFragment();
  }

  // provides ctrl+Q doc
  @Override
  public @Nls String generateDoc(@NotNull PsiElement element, @Nullable PsiElement originalElement) {
    final PythonRuntimeService runtimeService = PythonRuntimeService.getInstance();
    if (runtimeService.isInPydevConsole(element) || originalElement != null && runtimeService.isInPydevConsole(originalElement)) {
      return runtimeService.createPydevDoc(element, originalElement);
    }
    return new PyDocumentationBuilder(element, originalElement).build();
  }

  @Override
  public PsiElement getDocumentationElementForLink(PsiManager psiManager, @NotNull String link, @NotNull PsiElement context) {
    return PyDocumentationLink.elementForLink(link,
                                              context,
                                              TypeEvalContext.userInitiated(context.getProject(), context.getContainingFile()));
  }

  @Override
  public List<String> getUrlFor(PsiElement element, PsiElement originalElement) {
    final String url = getOnlyUrlFor(element, originalElement);
    return url == null ? null : Collections.singletonList(url);
  }

  @Nullable
  @Override
  public PsiElement getCustomDocumentationElement(@NotNull Editor editor,
                                                  @NotNull PsiFile file,
                                                  @Nullable PsiElement contextElement,
                                                  int targetOffset) {
    if (contextElement != null) {
      final IElementType elementType = contextElement.getNode().getElementType();
      if (PythonDialectsTokenSetProvider.getInstance().getKeywordTokens().contains(elementType)) {
        return contextElement;
      }
      final PsiElement parent = contextElement.getParent();
      if (parent instanceof PyArgumentList && (PyTokenTypes.LPAR == elementType || PyTokenTypes.RPAR == elementType)) {
        final PyCallExpression expression = PsiTreeUtil.getParentOfType(contextElement, PyCallExpression.class);
        if (expression != null) {
          final PyExpression callee = expression.getCallee();
          if (callee != null) {
            final PsiReference reference = callee.getReference();
            if (reference != null) {
              return reference.resolve();
            }
          }
        }
      }
      final PyExpression expression = PsiTreeUtil.getParentOfType(contextElement, PyExpression.class);
      if (expression != null && DocStringUtil.isDocStringExpression(expression)) {
        final PyDocStringOwner docstringOwner = PsiTreeUtil.getParentOfType(contextElement, PyDocStringOwner.class);
        if (docstringOwner != null) return docstringOwner;
      }
    }
    return null;
  }

  private static void appendWithTags(@NotNull StringBuilder result, @NotNull String escapedContent, String @NotNull ... tags) {
    for (String tag : tags) {
      result.append("<").append(tag).append(">");
    }
    result.append(escapedContent);
    for (int i = tags.length - 1; i >= 0; i--) {
      result.append("</").append(tags[i]).append(">");
    }
  }

  @NotNull
  private static String escaped(@NotNull String unescaped) {
    return StringUtil.escapeXmlEntities(unescaped);
  }

  @NotNull
  private static String formatTypeWithLinks(@Nullable PyType type,
                                            @Nullable PyTypedElement typeOwner,
                                            @NotNull PsiElement anchor,
                                            @NotNull TypeEvalContext context) {
    final HtmlBuilder builder = new HtmlBuilder();
    describeTypeWithLinks(type, typeOwner, context, anchor, builder);
    return builder.toString();
  }

  @Nullable
  public static QualifiedName getFullQualifiedName(@Nullable final PsiElement element) {
    final String name =
      (element instanceof PsiNamedElement) ? ((PsiNamedElement)element).getName() : element != null ? element.getText() : null;
    if (name != null) {
      final ScopeOwner owner = ScopeUtil.getScopeOwner(element);
      final PyBuiltinCache builtinCache = PyBuiltinCache.getInstance(element);
      if (owner instanceof PyClass) {
        final QualifiedName importQName = QualifiedNameFinder.findCanonicalImportPath(element, element);
        if (importQName != null) {
          return QualifiedName.fromDottedString(importQName.toString() + "." + owner.getName() + "." + name);
        }
      }
      else if (PyUtil.isInitOrNewMethod(owner)) {
        final QualifiedName importQName = QualifiedNameFinder.findCanonicalImportPath(owner, element);
        final PyClass containingClass = ((PyFunction)owner).getContainingClass();
        if (importQName != null && containingClass != null) {
          return QualifiedName.fromDottedString(importQName + "." + containingClass.getName() + "." + name);
        }
      }
      else if (owner instanceof PyFile) {
        if (builtinCache.isBuiltin(element)) {
          return QualifiedName.fromDottedString(name);
        }
        else {
          final VirtualFile virtualFile = ((PyFile)owner).getVirtualFile();
          if (virtualFile != null) {
            final QualifiedName fileQName = QualifiedNameFinder.findCanonicalImportPath(element, element);
            if (fileQName != null) {
              return QualifiedName.fromDottedString(fileQName + "." + name);
            }
          }
        }
      }
      else {
        if (element instanceof PyFile) {
          return QualifiedNameFinder.findCanonicalImportPath(element, element);
        }
      }
    }
    return null;
  }

  @Nullable
  protected static PsiFileSystemItem getFile(PsiElement element) {
    PsiFileSystemItem file = element instanceof PsiFileSystemItem ? (PsiFileSystemItem)element : element.getContainingFile();
    return (PsiFileSystemItem)PyUtil.turnInitIntoDir(file);
  }

  @Nullable
  public static PsiNamedElement getNamedElement(@Nullable PsiElement element) {
    PsiNamedElement namedElement = (element instanceof PsiNamedElement) ? (PsiNamedElement)element : null;
    final PyClass containingClass = PyUtil.turnConstructorIntoClass(as(namedElement, PyFunction.class));
    if (containingClass != null) {
      namedElement = containingClass;
    }
    else {
      namedElement = (PsiNamedElement)PyUtil.turnInitIntoDir(namedElement);
    }
    return namedElement;
  }

  @Nullable
  public static String getOnlyUrlFor(PsiElement element, PsiElement originalElement) {
    PsiFileSystemItem file = getFile(element);
    if (file == null) return null;
    final Sdk sdk = PyBuiltinCache.findSdkForFile(file);
    if (sdk == null) {
      return null;
    }
    final QualifiedName qName = QualifiedNameFinder.findCanonicalImportPath(element, originalElement);
    if (qName == null) {
      return null;
    }
    final PythonDocumentationMap map = PythonDocumentationMap.getInstance();
    final String pyVersion = pyVersion(sdk.getVersionString());
    PsiNamedElement namedElement = getNamedElement(element);
    final String url = map.urlFor(qName, namedElement, pyVersion);
    if (url != null) {
      return url;
    }
    for (PythonDocumentationLinkProvider provider : PythonDocumentationLinkProvider.EP_NAME.getExtensionList()) {
      final String providerUrl = provider.getExternalDocumentationUrl(element, originalElement);
      if (providerUrl != null) {
        return providerUrl;
      }
    }
    return null;
  }

  @Nullable
  public static String pyVersion(@Nullable String versionString) {
    final String prefix = "Python ";
    if (versionString != null && versionString.startsWith(prefix)) {
      final String version = versionString.substring(prefix.length());
      int dot = version.indexOf('.');
      if (dot > 0) {
        dot = version.indexOf('.', dot + 1);
        if (dot > 0) {
          return version.substring(0, dot);
        }
        return version;
      }
    }
    return null;
  }
}
