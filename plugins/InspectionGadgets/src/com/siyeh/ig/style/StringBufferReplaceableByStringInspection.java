// Copyright 2000-2017 JetBrains s.r.o. Use of this source code is governed by the Apache 2.0 license that can be found in the LICENSE file.
package com.siyeh.ig.style;

import com.intellij.application.options.CodeStyle;
import com.intellij.codeInspection.ProblemDescriptor;
import com.intellij.lang.java.JavaLanguage;
import com.intellij.openapi.editor.Document;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.text.StringUtil;
import com.intellij.psi.*;
import com.intellij.psi.codeStyle.JavaCodeStyleSettings;
import com.intellij.psi.util.PsiTreeUtil;
import com.intellij.psi.util.PsiUtil;
import com.intellij.util.SmartList;
import com.intellij.util.containers.ContainerUtil;
import com.siyeh.InspectionGadgetsBundle;
import com.siyeh.ig.BaseInspection;
import com.siyeh.ig.BaseInspectionVisitor;
import com.siyeh.ig.InspectionGadgetsFix;
import com.siyeh.ig.PsiReplacementUtil;
import com.siyeh.ig.callMatcher.CallMatcher;
import com.siyeh.ig.psiutils.ExpressionUtils;
import com.siyeh.ig.psiutils.ParenthesesUtils;
import com.siyeh.ig.psiutils.TypeUtils;
import org.jetbrains.annotations.NonNls;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Iterator;
import java.util.List;

/**
 * @author Bas Leijdekkers
 */
public class StringBufferReplaceableByStringInspection extends BaseInspection {

  static final String STRING_JOINER = "java.util.StringJoiner";
  private static final CallMatcher STRING_JOINER_ADD = CallMatcher.instanceCall(STRING_JOINER, "add").parameterCount(1);

  @Override
  protected InspectionGadgetsFix buildFix(Object... infos) {
    final String typeText = ((PsiType)infos[1]).getPresentableText();
    return new StringBufferReplaceableByStringFix(typeText);
  }

  @Override
  @NotNull
  public String getDisplayName() {
    return InspectionGadgetsBundle.message("string.buffer.replaceable.by.string.display.name");
  }

  @Override
  @NotNull
  public String buildErrorString(Object... infos) {
    final PsiElement element = (PsiElement)infos[0];
    if (element instanceof PsiNewExpression) {
      return InspectionGadgetsBundle.message("new.string.buffer.replaceable.by.string.problem.descriptor");
    }
    final String typeText = ((PsiType)infos[1]).getPresentableText();
    return InspectionGadgetsBundle.message("string.buffer.replaceable.by.string.problem.descriptor", typeText);
  }

  @Override
  public BaseInspectionVisitor buildVisitor() {
    return new StringBufferReplaceableByStringVisitor();
  }

  private static boolean isConcatenatorConstruction(PsiNewExpression expression) {
    PsiType type = expression.getType();
    if (TypeUtils.typeEquals(CommonClassNames.JAVA_LANG_STRING_BUFFER, type) ||
        TypeUtils.typeEquals(CommonClassNames.JAVA_LANG_STRING_BUILDER, type)) {
      return true;
    }
    if (TypeUtils.typeEquals(STRING_JOINER, type)) {
      PsiExpressionList args = expression.getArgumentList();
      if (args == null) return false;
      PsiExpression[] expressions = args.getExpressions();
      return expressions.length == 1 && ExpressionUtils.isLiteral(PsiUtil.skipParenthesizedExprDown(expressions[0]), "");
    }
    return false;
  }

  private static boolean isConcatenatorType(PsiType type) {
    return TypeUtils.typeEquals(CommonClassNames.JAVA_LANG_STRING_BUFFER, type) ||
           TypeUtils.typeEquals(CommonClassNames.JAVA_LANG_STRING_BUILDER, type) ||
           TypeUtils.typeEquals(STRING_JOINER, type);
  }

  static boolean isAppendCall(PsiElement element) {
    if (!(element instanceof PsiMethodCallExpression)) {
      return false;
    }
    final PsiMethodCallExpression methodCallExpression = (PsiMethodCallExpression)element;
    if (STRING_JOINER_ADD.test(methodCallExpression)) return true;
    final PsiReferenceExpression methodExpression = methodCallExpression.getMethodExpression();
    @NonNls final String methodName = methodExpression.getReferenceName();
    if (!"append".equals(methodName)) {
      return false;
    }
    final PsiExpressionList argumentList = methodCallExpression.getArgumentList();
    final PsiExpression[] arguments = argumentList.getExpressions();
    if (arguments.length == 3) {
      return arguments[0].getType() instanceof PsiArrayType &&
             PsiType.INT.equals(arguments[1].getType()) && PsiType.INT.equals(arguments[2].getType());
    }
    return arguments.length == 1;
  }

  private static boolean isToStringCall(PsiElement element) {
    if (!(element instanceof PsiMethodCallExpression)) {
      return false;
    }
    final PsiMethodCallExpression methodCallExpression = (PsiMethodCallExpression)element;
    final PsiReferenceExpression methodExpression = methodCallExpression.getMethodExpression();
    @NonNls final String methodName = methodExpression.getReferenceName();
    if (!"toString".equals(methodName)) {
      return false;
    }
    final PsiExpressionList argumentList = methodCallExpression.getArgumentList();
    final PsiExpression[] arguments = argumentList.getExpressions();
    return arguments.length == 0;
  }

  @Nullable
  static PsiExpression getCompleteExpression(PsiExpression qualifier) {
    PsiMethodCallExpression call;
    while (true) {
      call = ExpressionUtils.getCallForQualifier(qualifier);
      if (call == null) return null;
      if (isToStringCall(call)) {
        return call;
      }
      if (!isAppendCall(call)) return null;
      qualifier = call;
    }
  }

  private static class StringBufferReplaceableByStringFix extends InspectionGadgetsFix {

    private final List<PsiComment> leadingComments = new SmartList<>();
    private final List<PsiComment> comments = new SmartList<>();
    private final String myType;
    private int currentLine = -1;

    StringBufferReplaceableByStringFix(String type) {
      myType = type;
    }

    @NotNull
    @Override
    public String getName() {
      return InspectionGadgetsBundle.message("string.builder.replaceable.by.string.quickfix", myType);
    }

    @NotNull
    @Override
    public String getFamilyName() {
      return "Replace with 'String'";
    }

    @Override
    protected void doFix(Project project, ProblemDescriptor descriptor) {
      final PsiElement element = descriptor.getPsiElement();
      final PsiElement parent = element.getParent();
      if (!(parent instanceof PsiVariable)) {
        if (parent instanceof PsiNewExpression) {
          final PsiExpression stringBuilderExpression = getCompleteExpression((PsiExpression)parent);
          collectComments(stringBuilderExpression);
          final StringBuilder stringExpression = buildStringExpression(stringBuilderExpression, new StringBuilder());
          if (stringExpression != null && stringBuilderExpression != null) {
            addLeadingCommentsBefore(stringBuilderExpression);
            addTrailingCommentsAfter(stringBuilderExpression);
            PsiReplacementUtil.replaceExpression(stringBuilderExpression, stringExpression.toString());
          }
        }
        return;
      }
      final PsiVariable variable = (PsiVariable)parent;
      final String variableName = variable.getName();
      if (variableName == null) {
        return;
      }
      final PsiTypeElement originalTypeElement = variable.getTypeElement();
      if (originalTypeElement == null) {
        return;
      }
      final PsiExpression initializer = PsiUtil.skipParenthesizedExprDown(variable.getInitializer());
      if (initializer == null) {
        return;
      }
      final StringBuilder builder;
      if (isAppendCall(initializer)) {
        collectComments(parent);
        builder = buildStringExpression(initializer, new StringBuilder());
        if (builder == null) {
          return;
        }
      } else if (initializer instanceof PsiNewExpression) {
        final PsiNewExpression newExpression = (PsiNewExpression)initializer;
        final PsiExpressionList argumentList = newExpression.getArgumentList();
        if (argumentList == null) {
          return;
        }
        final PsiExpression[] arguments = argumentList.getExpressions();
        if (arguments.length == 0 || TypeUtils.typeEquals(STRING_JOINER, newExpression.getType())) {
          builder = new StringBuilder();
        }
        else {
          final PsiExpression argument = arguments[0];
          if (PsiType.INT.equals(argument.getType())) {
            builder = new StringBuilder();
          }
          else if (ParenthesesUtils.getPrecedence(argument) > ParenthesesUtils.ADDITIVE_PRECEDENCE) {
            builder = new StringBuilder("(").append(argument.getText()).append(')');
          }
          else {
            builder = new StringBuilder(argument.getText());
          }
        }
      } else {
        return;
      }
      final PsiCodeBlock codeBlock = PsiTreeUtil.getParentOfType(variable, PsiCodeBlock.class);
      if (codeBlock == null) {
        return;
      }
      final StringBuildingVisitor visitor = new StringBuildingVisitor(variable, builder);
      codeBlock.accept(visitor);
      if (visitor.hadProblem()) {
        return;
      }
      final List<PsiMethodCallExpression> expressions = visitor.getExpressions();
      final String expressionText = builder.toString().trim();
      final PsiMethodCallExpression lastExpression = expressions.get(expressions.size() - 1);
      final PsiStatement statement = PsiTreeUtil.getParentOfType(lastExpression, PsiStatement.class);
      if (statement == null) {
        return;
      }
      final boolean useVariable = expressionText.contains("\n") && !isVariableInitializer(lastExpression);
      if (useVariable) {
        final String modifier = JavaCodeStyleSettings.getInstance(element.getContainingFile()).GENERATE_FINAL_LOCALS ? "final " : "";
        final PsiElementFactory factory = JavaPsiFacade.getElementFactory(project);
        final StringBuilder statementText =
          new StringBuilder(modifier).append(CommonClassNames.JAVA_LANG_STRING).append(' ').append(variableName).append("=");
        for (PsiComment comment : leadingComments) {
          statementText.append(comment.getText());
          final PsiElement sibling = comment.getNextSibling();
          if (sibling instanceof PsiWhiteSpace) {
            statementText.append(sibling.getText());
          }
        }
        statementText.append(expressionText).append(';');
        final PsiStatement newStatement = factory.createStatementFromText(statementText.toString(), variable);
        codeBlock.addBefore(newStatement, statement);
        addTrailingCommentsAfter(lastExpression);
        PsiReplacementUtil.replaceExpression(lastExpression, variableName);
      }
      else {
        addLeadingCommentsBefore(statement);
        addTrailingCommentsAfter(statement);
        PsiReplacementUtil.replaceExpression(lastExpression, expressionText);
      }
      variable.delete();
      for (int i = 0, size = expressions.size() - 1; i < size; i++) {
        expressions.get(i).getParent().delete();
      }
    }

    private static boolean isVariableInitializer(PsiExpression expression) {
      final PsiElement parent = expression.getParent();
      if (!(parent instanceof PsiVariable)) {
        return false;
      }
      final PsiVariable variable = (PsiVariable)parent;
      final PsiExpression initializer = variable.getInitializer();
      return initializer == expression;
    }

    @Nullable
    StringBuilder buildStringExpression(PsiElement element, @NonNls StringBuilder result) {
      if (currentLine < 0) {
        currentLine = getLineNumber(element);
      }
      if (element instanceof PsiNewExpression) {
        final PsiNewExpression newExpression = (PsiNewExpression)element;
        final PsiExpressionList argumentList = newExpression.getArgumentList();
        if (argumentList == null) {
          return null;
        }
        addCommentsBefore(argumentList, false, result);
        final PsiExpression[] arguments = argumentList.getExpressions();
        if (arguments.length == 1 && !TypeUtils.typeEquals(STRING_JOINER, newExpression.getType())) {
          final PsiExpression argument = arguments[0];
          final PsiType type = argument.getType();
          if (!PsiType.INT.equals(type)) {
            if (type != null && type.equalsToText("java.lang.CharSequence")) {
              result.append("String.valueOf(").append(argument.getText()).append(')');
            }
            else if (ParenthesesUtils.getPrecedence(argument) > ParenthesesUtils.ADDITIVE_PRECEDENCE) {
              result.append('(').append(argument.getText()).append(')');
            }
            else {
              result.append(argument.getText());
            }
          }
        }
        return result;
      }
      for (PsiElement child : element.getChildren()) {
        if (child instanceof PsiExpressionList) {
          continue;
        }
        if (buildStringExpression(child, result) == null) {
          return null;
        }
      }

      if (element instanceof PsiMethodCallExpression) {
        final PsiMethodCallExpression methodCallExpression = (PsiMethodCallExpression)element;
        final PsiExpressionList argumentList = methodCallExpression.getArgumentList();
        final PsiReferenceExpression methodExpression = methodCallExpression.getMethodExpression();
        final String referenceName = methodExpression.getReferenceName();
        if ("toString".equals(referenceName)) {
          if (result.length() == 0) {
            result.append("\"\"");
          }
        }
        else if ("append".equals(referenceName) || "add".equals(referenceName)){
          final PsiExpression[] arguments = argumentList.getExpressions();
          if (arguments.length == 0) {
            return null;
          }
          if (arguments.length > 1) {
            addCommentsBefore(argumentList, result.length() != 0, result);
            result.append("String.valueOf").append(argumentList.getText());
            return result;
          }
          final PsiExpression argument = arguments[0];
          final PsiType type = argument.getType();
          final String argumentText = argument.getText();
          if (result.length() != 0) {
            addCommentsBefore(argument, true, result);
            if (ParenthesesUtils.getPrecedence(argument) > ParenthesesUtils.ADDITIVE_PRECEDENCE ||
                (type instanceof PsiPrimitiveType && ParenthesesUtils.getPrecedence(argument) == ParenthesesUtils.ADDITIVE_PRECEDENCE)) {
              result.append('(').append(argumentText).append(')');
            }
            else {
              if (type instanceof PsiArrayType) {
                result.append("String.valueOf(").append(argumentText).append(")");
              }
              else {
                if (StringUtil.startsWithChar(argumentText, '+')) {
                  result.append(' ');
                }
                result.append(argumentText);
              }
            }
          }
          else {
            addCommentsBefore(argumentList, false, result);
            if (type instanceof PsiPrimitiveType) {
              if (argument instanceof PsiLiteralExpression) {
                final PsiLiteralExpression literalExpression = (PsiLiteralExpression)argument;
                if (PsiType.CHAR.equals(literalExpression.getType())) {
                  result.append('"');
                  final Character c = (Character)literalExpression.getValue();
                  if (c != null) {
                    result.append(StringUtil.escapeStringCharacters(c.toString()));
                  }
                  result.append('"');
                }
                else {
                  result.append('"').append(literalExpression.getValue()).append('"');
                }
              }
              else {
                result.append("String.valueOf(").append(argumentText).append(")");
              }
            }
            else {
              if (ParenthesesUtils.getPrecedence(argument) >= ParenthesesUtils.ADDITIVE_PRECEDENCE) {
                result.append('(').append(argumentText).append(')');
              }
              else {
                if (type != null && !type.equalsToText(CommonClassNames.JAVA_LANG_STRING)) {
                  result.append("String.valueOf(").append(argumentText).append(")");
                }
                else {
                  result.append(argumentText);
                }
              }
            }
          }
        }
      }
      return result;
    }

    private void addCommentsBefore(PsiElement anchor, boolean insertPlus, StringBuilder out) {
      final boolean operationSignOnNextLine =
        CodeStyle.getLanguageSettings(anchor.getContainingFile(), JavaLanguage.INSTANCE).BINARY_OPERATION_SIGN_ON_NEXT_LINE;
      final int lineNumber = getLineNumber(anchor);
      boolean insertNewLine = currentLine != lineNumber;
      currentLine = lineNumber;
      final int offset = anchor.getTextOffset();
      if (insertPlus && !operationSignOnNextLine) {
        out.append('+');
      }
      List<PsiComment> commentsToInsert = new SmartList<>();
      for (final Iterator<PsiComment> iterator = comments.iterator(); iterator.hasNext(); ) {
        final PsiComment comment = iterator.next();
        if (comment.getTextOffset() >= offset) {
          break;
        }
        if (out.length() == 0) {
          leadingComments.add(comment);
        }
        else {
          commentsToInsert.add(comment);
        }
        iterator.remove();
      }
      for (int i = 0; i < commentsToInsert.size(); i++) {
        PsiComment comment = commentsToInsert.get(i);
        final PsiElement prev = comment.getPrevSibling();
        if (prev instanceof PsiWhiteSpace) {
          out.append(prev.getText());
        }
        out.append(comment.getText());
        final PsiElement next = comment.getNextSibling();
        if (next instanceof PsiWhiteSpace) {
          final String text = next.getText();
          if (!text.contains("\n") || i < commentsToInsert.size() - 1) {
            out.append(text);
          }
        }
      }
      if (insertNewLine && out.length() > 0) {
        out.append('\n');
      }
      if (insertPlus && operationSignOnNextLine) {
        out.append('+');
      }
    }

    private void addLeadingCommentsBefore(PsiElement anchor) {
      final PsiElement parent = anchor.getParent();
      for (PsiComment comment : leadingComments) {
        parent.addBefore(comment, anchor);
        final PsiElement sibling = comment.getNextSibling();
        if (sibling instanceof PsiWhiteSpace) {
          parent.addBefore(sibling, anchor);
        }
      }
      leadingComments.clear();
    }

    private void addTrailingCommentsAfter(PsiElement anchor) {
      final PsiElement parent = anchor.getParent();
      for (int i = comments.size() - 1; i >= 0; i--) {
        final PsiComment comment = comments.get(i);
        parent.addAfter(comment, anchor);
        final PsiElement sibling = comment.getPrevSibling();
        if (sibling instanceof PsiWhiteSpace) {
          parent.addAfter(sibling, anchor);
        }
      }
      comments.clear();
    }

    void collectComments(PsiElement element) {
      comments.addAll(PsiTreeUtil.findChildrenOfType(element, PsiComment.class));
      final PsiElement parent = element.getParent();
      if (!(parent instanceof PsiExpressionStatement) && !(parent instanceof PsiDeclarationStatement)) {
        return;
      }
      PsiComment comment = PsiTreeUtil.getNextSiblingOfType(element, PsiComment.class);
      while (comment != null) {
        comments.add(comment);
        comment = PsiTreeUtil.getNextSiblingOfType(comment, PsiComment.class);
      }
    }

    private static int getLineNumber(PsiElement element) {
      final Document document = PsiDocumentManager.getInstance(element.getProject()).getDocument(element.getContainingFile());
      assert document != null;
      return document.getLineNumber(element.getTextRange().getStartOffset());
    }

    private class StringBuildingVisitor extends JavaRecursiveElementWalkingVisitor {

      private final PsiVariable myVariable;
      private final StringBuilder myBuilder;
      private final List<PsiMethodCallExpression> expressions = ContainerUtil.newArrayList();
      private boolean myProblem;

      StringBuildingVisitor(@NotNull PsiVariable variable, StringBuilder builder) {
        myVariable = variable;
        myBuilder = builder;
      }

      @Override
      public void visitReferenceExpression(PsiReferenceExpression expression) {
        if (myProblem) {
          return;
        }
        super.visitReferenceExpression(expression);
        if (expression.getQualifierExpression() != null) {
          return;
        }
        final PsiElement target = expression.resolve();
        if (!myVariable.equals(target)) {
          return;
        }
        PsiMethodCallExpression methodCallExpression = null;
        PsiElement parent = expression.getParent();
        PsiElement grandParent = parent.getParent();
        while (parent instanceof PsiReferenceExpression && grandParent instanceof PsiMethodCallExpression) {
          methodCallExpression = (PsiMethodCallExpression)grandParent;
          parent = methodCallExpression.getParent();
          grandParent = parent.getParent();
          if ("toString".equals(methodCallExpression.getMethodExpression().getReferenceName())) {
            break;
          }
        }
        collectComments(methodCallExpression);
        if (buildStringExpression(methodCallExpression, myBuilder) == null) {
          myProblem = true;
        }
        expressions.add(methodCallExpression);
      }

      public List<PsiMethodCallExpression> getExpressions() {
        return expressions;
      }

      boolean hadProblem() {
        return myProblem;
      }
    }
  }

  private static class StringBufferReplaceableByStringVisitor extends BaseInspectionVisitor {

    @Override
    public void visitLocalVariable(@NotNull PsiLocalVariable variable) {
      super.visitLocalVariable(variable);
      final PsiType type = variable.getType();
      if (!isConcatenatorType(type)) return;
      final PsiExpression initializer = PsiUtil.skipParenthesizedExprDown(variable.getInitializer());
      if (!isNewStringConcatenatorChain(initializer)) return;
      final PsiElement codeBlock = PsiUtil.getVariableCodeBlock(variable, null);
      if (codeBlock == null) return;
      final ReplaceableByStringVisitor visitor = new ReplaceableByStringVisitor(variable);
      codeBlock.accept(visitor);
      if (!visitor.isReplaceable()) return;
      registerVariableError(variable, variable, type);
    }

    @Override
    public void visitNewExpression(PsiNewExpression expression) {
      super.visitNewExpression(expression);
      final PsiType type = expression.getType();
      if (!isConcatenatorConstruction(expression)) return;
      final PsiExpression completeExpression = getCompleteExpression(expression);
      if (completeExpression == null) return;
      registerNewExpressionError(expression, expression, type);
    }

    private static boolean isNewStringConcatenatorChain(PsiExpression expression) {
      while (true) {
        if (expression == null) return false;
        if (expression instanceof PsiNewExpression) {
          return isConcatenatorConstruction((PsiNewExpression)expression);
        }
        if (!(expression instanceof PsiMethodCallExpression)) return false;
        final PsiMethodCallExpression call = (PsiMethodCallExpression)expression;
        if (!isAppendCall(call)) return false;
        expression = PsiUtil.skipParenthesizedExprDown(call.getMethodExpression().getQualifierExpression());
      }
    }
  }

  private static class ReplaceableByStringVisitor extends JavaRecursiveElementWalkingVisitor {

    private final PsiElement myParent;
    private final PsiVariable myVariable;
    private boolean myReplaceable = true;
    private boolean myPossibleSideEffect;
    private boolean myToStringFound;

    ReplaceableByStringVisitor(@NotNull PsiVariable variable) {
      myVariable = variable;
      myParent = PsiTreeUtil.getParentOfType(variable, PsiCodeBlock.class, PsiIfStatement.class, PsiLoopStatement.class);
    }

    public boolean isReplaceable() {
      return myReplaceable && myToStringFound;
    }

    @Override
    public void visitElement(PsiElement element) {
      if (!myReplaceable) {
        return;
      }
      super.visitElement(element);
    }

    @Override
    public void visitAssignmentExpression(PsiAssignmentExpression expression) {
      super.visitAssignmentExpression(expression);
      if (expression.getTextOffset() > myVariable.getTextOffset() && !myToStringFound) {
        myPossibleSideEffect = true;
      }
    }

    @Override
    public void visitUnaryExpression(PsiUnaryExpression expression) {
      super.visitUnaryExpression(expression);
      if (expression.getTextOffset() > myVariable.getTextOffset() && !myToStringFound) {
        myPossibleSideEffect = true;
      }
    }

    @Override
    public void visitMethodCallExpression(PsiMethodCallExpression expression) {
      super.visitMethodCallExpression(expression);
      if (expression.getTextOffset() < myVariable.getTextOffset() || myToStringFound) {
        return;
      }
      final PsiMethod method = expression.resolveMethod();
      if (method == null) {
        myPossibleSideEffect = true;
        return;
      }
      final PsiClass aClass = method.getContainingClass();
      if (aClass == null) {
        myPossibleSideEffect = true;
        return;
      }
      final String name = aClass.getQualifiedName();
      if (CommonClassNames.JAVA_LANG_STRING_BUFFER.equals(name) ||
        CommonClassNames.JAVA_LANG_STRING_BUILDER.equals(name) ||
        STRING_JOINER.equals(name)) {
        return;
      }
      if (isArgumentOfStringBuilderMethod(expression)) {
        return;
      }
      myPossibleSideEffect = true;
    }

    private boolean isArgumentOfStringBuilderMethod(PsiMethodCallExpression expression) {
      final PsiExpressionList parent = PsiTreeUtil.getParentOfType(expression, PsiExpressionList.class, true, PsiStatement.class);
      if (parent == null) {
        return false;
      }
      final PsiElement grandParent = parent.getParent();
      if (grandParent instanceof PsiMethodCallExpression) {
        final PsiMethodCallExpression methodCallExpression = (PsiMethodCallExpression)grandParent;
        return isCallToStringBuilderMethod(methodCallExpression) || isArgumentOfStringBuilderMethod(methodCallExpression);
      }
      if (grandParent instanceof PsiNewExpression) {
        final PsiLocalVariable variable = PsiTreeUtil.getParentOfType(grandParent, PsiLocalVariable.class, true, PsiExpressionList.class);
        if (!myVariable.equals(variable)) {
          return false;
        }
        final PsiNewExpression newExpression = (PsiNewExpression)grandParent;
        final PsiMethod constructor = newExpression.resolveMethod();
        if (constructor == null) {
          return false;
        }
        final PsiClass aClass = constructor.getContainingClass();
        if (aClass == null) {
          return false;
        }
        final String name = aClass.getQualifiedName();
        return CommonClassNames.JAVA_LANG_STRING_BUFFER.equals(name) ||
               CommonClassNames.JAVA_LANG_STRING_BUILDER.equals(name);
      }
      return false;
    }

    private boolean isCallToStringBuilderMethod(PsiMethodCallExpression methodCallExpression) {
      final PsiReferenceExpression methodExpression = methodCallExpression.getMethodExpression();
      PsiExpression qualifier = PsiUtil.skipParenthesizedExprDown(methodExpression.getQualifierExpression());
      while (qualifier instanceof PsiMethodCallExpression) {
        final PsiMethodCallExpression call = (PsiMethodCallExpression)qualifier;
        qualifier = PsiUtil.skipParenthesizedExprDown(call.getMethodExpression().getQualifierExpression());
      }
      if (!(qualifier instanceof PsiReferenceExpression)) {
        return false;
      }
      final PsiReferenceExpression referenceExpression = (PsiReferenceExpression)qualifier;
      final PsiElement target = referenceExpression.resolve();
      if (!myVariable.equals(target)) {
        return false;
      }
      final PsiMethod method = methodCallExpression.resolveMethod();
      if (method == null) {
        return false;
      }
      final PsiClass aClass = method.getContainingClass();
      if (aClass == null) {
        return false;
      }
      final String name1 = aClass.getQualifiedName();
      return CommonClassNames.JAVA_LANG_STRING_BUFFER.equals(name1) ||
             CommonClassNames.JAVA_LANG_STRING_BUILDER.equals(name1);
    }

    @Override
    public void visitReferenceExpression(PsiReferenceExpression expression) {
      if (!myReplaceable || expression.getTextOffset() < myVariable.getTextOffset()) {
        return;
      }
      super.visitReferenceExpression(expression);
      final PsiExpression qualifier = expression.getQualifierExpression();
      if (qualifier != null || !expression.isReferenceTo(myVariable)) return;
      if (myToStringFound) {
        myReplaceable = false;
        return;
      }
      final PsiElement element = PsiTreeUtil.getParentOfType(expression, PsiCodeBlock.class, PsiIfStatement.class, PsiLoopStatement.class);
      if (!myParent.equals(element)) {
        myReplaceable = false;
        return;
      }
      PsiElement parent = expression.getParent();
      while (true) {
        if (!(parent instanceof PsiReferenceExpression)) {
          myReplaceable = false;
          return;
        }
        final PsiElement grandParent = parent.getParent();
        if (!isAppendCall(grandParent)) {
          if (!isToStringCall(grandParent)) {
            myReplaceable = false;
            return;
          }
          myToStringFound = true;
          return;
        }
        if (myPossibleSideEffect) {
          myReplaceable = false;
          return;
        }
        parent = grandParent.getParent();
        if (parent instanceof PsiExpressionStatement) {
          return;
        }
      }
    }
  }
}
