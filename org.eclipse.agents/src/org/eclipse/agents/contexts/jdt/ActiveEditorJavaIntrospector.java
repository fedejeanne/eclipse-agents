package org.eclipse.agents.contexts.jdt;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.eclipse.core.resources.IFile;
import org.eclipse.core.resources.IProject;
import org.eclipse.core.runtime.Adapters;
import org.eclipse.jdt.core.ICompilationUnit;
import org.eclipse.jdt.core.IJavaProject;
import org.eclipse.jdt.core.JavaCore;
import org.eclipse.jdt.core.dom.*;
import org.eclipse.jface.text.IDocument;
import org.eclipse.ui.*;
import org.eclipse.ui.texteditor.IDocumentProvider;
import org.eclipse.ui.texteditor.ITextEditor;

/**
 * End-to-end: active editor -> (unsaved) document text -> JDT AST -> structured data (JSON-like map).
 *
 * This works for:
 * - normal Java editors
 * - unsaved buffers (uses editor document text)
 * - nested/inner types
 *
 * Limits:
 * - If the file isn't in a Java project / build path, binding resolution may be partial.
 * - Javadoc extraction here is "raw text" via node.getJavadoc().toString(). You can parse tags if needed.
 */
public final class ActiveEditorJavaIntrospector {

  private ActiveEditorJavaIntrospector() {}

  // ---------- Public API ----------

  /** Return a Map that is easy to serialize as JSON (e.g., by your MCP framework). */
  public static Map<String, Object> snapshotActiveEditor() {
    IEditorPart editor = getActiveEditor();
    if (editor == null) {
      return error("No active editor.");
    }

    IEditorInput input = editor.getEditorInput();
    IFile file = adapt(input, IFile.class);
    if (file == null) {
      return error("Active editor is not backed by an IFile.");
    }

    String source = readEditorDocument(editor);
    if (source == null) {
      // fallback: best effort from compilation unit (might miss unsaved changes)
      ICompilationUnit cu = toCompilationUnit(file);
      if (cu == null) return error("Not a Java source file (.java) or not in a Java project.");
      try {
        source = cu.getSource();
      } catch (Exception e) {
        return error("Failed to read source: " + e.getMessage());
      }
    }

    IJavaProject javaProject = toJavaProject(file.getProject());

    CompilationUnit ast = parseToAst(source, file.getName(), javaProject);

    // Build a JSON-like result
    Map<String, Object> out = new LinkedHashMap<>();
    out.put("fileUri", file.getLocationURI().toString());
    out.put("project", file.getProject().getName());
    out.put("path", file.getProjectRelativePath().toString());

    List<Map<String, Object>> types = new ArrayList<>();
    out.put("types", types);

    ast.accept(new ASTVisitor(true) {

      @Override
      public boolean visit(TypeDeclaration node) {
        types.add(typeToMap(node, ast));
        return true; // keep walking for nested types too
      }

      @Override
      public boolean visit(EnumDeclaration node) {
        types.add(enumToMap(node, ast));
        return true;
      }

      @Override
      public boolean visit(AnnotationTypeDeclaration node) {
        types.add(annotationTypeToMap(node, ast));
        return true;
      }
    });

    return out;
  }

  // ---------- Parsing ----------

  private static CompilationUnit parseToAst(String source, String unitName, IJavaProject javaProject) {
    ASTParser parser = ASTParser.newParser(AST.getJLSLatest());
    parser.setKind(ASTParser.K_COMPILATION_UNIT);
    parser.setSource(source.toCharArray());
    parser.setUnitName(unitName);

    parser.setResolveBindings(true);
    parser.setBindingsRecovery(true);
    parser.setStatementsRecovery(true);

    if (javaProject != null) {
      parser.setProject(javaProject);
      // optional but helps keep parsing consistent with workspace:
      Map<String, String> options = javaProject.getOptions(true);
      parser.setCompilerOptions(options);
    }

    return (CompilationUnit) parser.createAST(null);
  }

  // ---------- Conversion helpers (AST -> Map) ----------

  private static Map<String, Object> typeToMap(TypeDeclaration node, CompilationUnit cu) {
    Map<String, Object> t = new LinkedHashMap<>();
    t.put("kind", "classOrInterface");
    t.put("name", node.getName().getIdentifier());
    t.put("isInterface", node.isInterface());
    t.put("modifiers", modifiersToStrings(node.modifiers()));
    t.put("range", range(node, cu));
    t.put("javadoc", javadocText(node.getJavadoc()));

    // extends / implements
    if (node.getSuperclassType() != null) t.put("extends", node.getSuperclassType().toString());
    @SuppressWarnings("unchecked")
    List<Type> ifaces = node.superInterfaceTypes();
    if (!ifaces.isEmpty()) {
      List<String> impl = new ArrayList<>();
      for (Type it : ifaces) impl.add(it.toString());
      t.put("implements", impl);
    }

    List<Map<String, Object>> fields = new ArrayList<>();
    List<Map<String, Object>> methods = new ArrayList<>();
    List<Map<String, Object>> nested = new ArrayList<>();
    t.put("fields", fields);
    t.put("methods", methods);
    t.put("nestedTypes", nested);

    for (FieldDeclaration fd : node.getFields()) {
      fields.addAll(fieldDeclToMaps(fd, cu));
    }
    for (MethodDeclaration md : node.getMethods()) {
      methods.add(methodToMap(md, cu));
    }
    for (TypeDeclaration nt : node.getTypes()) {
      nested.add(typeToMap(nt, cu));
    }

    return t;
  }

  private static Map<String, Object> enumToMap(EnumDeclaration node, CompilationUnit cu) {
    Map<String, Object> t = new LinkedHashMap<>();
    t.put("kind", "enum");
    t.put("name", node.getName().getIdentifier());
    t.put("modifiers", modifiersToStrings(node.modifiers()));
    t.put("range", range(node, cu));
    t.put("javadoc", javadocText(node.getJavadoc()));

    List<String> constants = new ArrayList<>();
    @SuppressWarnings("unchecked")
    List<EnumConstantDeclaration> ecs = node.enumConstants();
    for (EnumConstantDeclaration ec : ecs) constants.add(ec.getName().getIdentifier());
    t.put("constants", constants);

    List<Map<String, Object>> methods = new ArrayList<>();
    List<Map<String, Object>> fields = new ArrayList<>();
    t.put("methods", methods);
    t.put("fields", fields);

    @SuppressWarnings("unchecked")
    List<BodyDeclaration> body = node.bodyDeclarations();
    for (BodyDeclaration bd : body) {
      if (bd instanceof MethodDeclaration md) methods.add(methodToMap(md, cu));
      if (bd instanceof FieldDeclaration fd) fields.addAll(fieldDeclToMaps(fd, cu));
    }

    return t;
  }

  private static Map<String, Object> annotationTypeToMap(AnnotationTypeDeclaration node, CompilationUnit cu) {
    Map<String, Object> t = new LinkedHashMap<>();
    t.put("kind", "annotation");
    t.put("name", node.getName().getIdentifier());
    t.put("modifiers", modifiersToStrings(node.modifiers()));
    t.put("range", range(node, cu));
    t.put("javadoc", javadocText(node.getJavadoc()));

    List<Map<String, Object>> members = new ArrayList<>();
    t.put("members", members);

    @SuppressWarnings("unchecked")
    List<BodyDeclaration> bds = node.bodyDeclarations();
    for (BodyDeclaration bd : bds) {
      if (bd instanceof AnnotationTypeMemberDeclaration amd) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("name", amd.getName().getIdentifier());
        m.put("type", amd.getType().toString());
        m.put("default", amd.getDefault() != null ? amd.getDefault().toString() : null);
        m.put("modifiers", modifiersToStrings(amd.modifiers()));
        m.put("range", range(amd, cu));
        m.put("javadoc", javadocText(amd.getJavadoc()));
        members.add(m);
      }
    }

    return t;
  }

  private static List<Map<String, Object>> fieldDeclToMaps(FieldDeclaration node, CompilationUnit cu) {
    List<Map<String, Object>> out = new ArrayList<>();
    String type = node.getType().toString();

    @SuppressWarnings("unchecked")
    List<VariableDeclarationFragment> frags = node.fragments();
    for (VariableDeclarationFragment f : frags) {
      Map<String, Object> field = new LinkedHashMap<>();
      field.put("name", f.getName().getIdentifier());
      field.put("type", type);
      field.put("modifiers", modifiersToStrings(node.modifiers()));
      field.put("range", range(f, cu));
      field.put("javadoc", javadocText(node.getJavadoc())); // javadoc is on the declaration, not fragment
      field.put("initializer", f.getInitializer() != null ? f.getInitializer().toString() : null);
      out.add(field);
    }

    return out;
  }

  private static Map<String, Object> methodToMap(MethodDeclaration node, CompilationUnit cu) {
    Map<String, Object> m = new LinkedHashMap<>();
    m.put("name", node.getName().getIdentifier());
    m.put("isConstructor", node.isConstructor());
    m.put("modifiers", modifiersToStrings(node.modifiers()));
    m.put("range", range(node, cu));
    m.put("javadoc", javadocText(node.getJavadoc()));

    if (!node.isConstructor()) {
      m.put("returnType", node.getReturnType2() != null ? node.getReturnType2().toString() : "void");
    }

    // parameters (names + types)
    List<Map<String, Object>> params = new ArrayList<>();
    @SuppressWarnings("unchecked")
    List<SingleVariableDeclaration> ps = node.parameters();
    for (SingleVariableDeclaration p : ps) {
      Map<String, Object> pm = new LinkedHashMap<>();
      pm.put("name", p.getName().getIdentifier());
      pm.put("type", p.getType().toString() + (p.isVarargs() ? "..." : ""));
      pm.put("modifiers", modifiersToStrings(p.modifiers()));
      pm.put("range", range(p, cu));
      params.add(pm);
    }
    m.put("parameters", params);

    // thrown exceptions
    List<String> thrown = new ArrayList<>();
    @SuppressWarnings("unchecked")
    List<Type> thrownTypes = node.thrownExceptionTypes();
    for (Type t : thrownTypes) thrown.add(t.toString());
    if (!thrown.isEmpty()) m.put("throws", thrown);

    return m;
  }

  private static List<String> modifiersToStrings(@SuppressWarnings("rawtypes") List mods) {
    List<String> out = new ArrayList<>();
    for (Object o : mods) {
      if (o instanceof Modifier m) out.add(m.getKeyword().toString());
      else out.add(o.toString()); // annotations show up here too
    }
    return out;
  }

  private static String javadocText(Javadoc jd) {
    return jd != null ? jd.toString() : null;
  }

  /**
   * Range includes offsets + line/col (1-based lines, 1-based columns).
   * Handy for linking back to the editor.
   */
  private static Map<String, Object> range(ASTNode node, CompilationUnit cu) {
    int start = node.getStartPosition();
    int len = node.getLength();
    int end = start + Math.max(len, 0);

    Map<String, Object> r = new LinkedHashMap<>();
    r.put("startOffset", start);
    r.put("endOffset", end);

    r.put("startLine", cu.getLineNumber(start));
    r.put("startColumn", cu.getColumnNumber(start) + 1);

    // end-1 because line/col for the first char *after* node is less useful
    int last = Math.max(start, end - 1);
    r.put("endLine", cu.getLineNumber(last));
    r.put("endColumn", cu.getColumnNumber(last) + 1);

    return r;
  }

  // ---------- Editor / JDT plumbing ----------

  private static IEditorPart getActiveEditor() {
    IWorkbench workbench = PlatformUI.getWorkbench();
    IWorkbenchWindow window = workbench != null ? workbench.getActiveWorkbenchWindow() : null;
    IWorkbenchPage page = window != null ? window.getActivePage() : null;
    return page != null ? page.getActiveEditor() : null;
  }

  private static String readEditorDocument(IEditorPart editor) {
    // Works for JDT and any text editor
    ITextEditor textEditor = adapt(editor, ITextEditor.class);
    if (textEditor == null) return null;

    IDocumentProvider dp = textEditor.getDocumentProvider();
    if (dp == null) return null;

    IDocument doc = dp.getDocument(textEditor.getEditorInput());
    return doc != null ? doc.get() : null;
  }

  private static ICompilationUnit toCompilationUnit(IFile file) {
    if (file == null || !"java".equalsIgnoreCase(file.getFileExtension())) return null;
    if (!file.getProject().isAccessible()) return null;

    IJavaProject jp = toJavaProject(file.getProject());
    if (jp == null) return null;

    // JavaCore.create(IFile) returns an ICompilationUnit for .java
    var je = JavaCore.create(file);
    return (je instanceof ICompilationUnit cu) ? cu : null;
  }

  private static IJavaProject toJavaProject(IProject project) {
    if (project == null || !project.isAccessible()) return null;
    return JavaCore.create(project);
  }

  private static Map<String, Object> error(String msg) {
    Map<String, Object> e = new LinkedHashMap<>();
    e.put("error", msg);
    return e;
  }

  private static <T> T adapt(Object o, Class<T> type) {
    if (o == null) return null;
    T adapted = Adapters.adapt(o, type);
    if (adapted != null) return adapted;
    if (type.isInstance(o)) return type.cast(o);
    return null;
  }
}