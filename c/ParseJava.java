package yeti.lang.compiler;

import yeti.renamed.asm3.Opcodes;
import java.util.*;

/*
XXX .* imports mean we can't put classes in map, but have to try all packages
instead, when class name is referenced. Which (in turn) means, that we have to
run all the shit in 2 pass - 1 to parse all classes, and 2 to resolve the id's
when we finally know what classes exist in which packages. It isn't all bad,
though - I think that resolve pass can be done lazily on-demand, when class
description is requested.
Finally, we have the weird foopkg.someclass.subclass; import shit.
I think this also couldn't be fully resolved without trying (and having all
classes known) - you have to first guess, that it's foopkg/someclass/subclass,
and if such beast is not found, try foopkg/somclass$subclass, and after that
foopkg$someclass$subclass. If none of those is found, you have to give up and
declare it to be unknown (for our purposes, a wild guess about first variant
could be done here).
In conclusion - we shouldn't try to resolve class-names at all in the first
parse stage, and just add import lists with classes. When class is required
at later stage the names can be resolved (as all classes are known then).

In-class st has basically easy life.

Modifiers:
abstract public protected package private static
final synchronized transient volatile strictfp native

modifier sets flag
class at cl1 -> expect name
id at cl1 -> type = id; st = cl2
< at cl2  -> skip until matching >, ;{ stops
id at cl2 -> name = id; st = cl3
= at cl3  -> st = cl4
( at cl3  -> st = cl5, read arglist

classes are only true nested shit for us - but we can cheat
using linked-list by having outer field in our class
structure and storing current class ptr.

XXX we can exploit MOD TYPE NAME pattern in java syntax to use same
code to parse fields, methods and method arguments.
XXX in fields , separated lists must create multiple class fields
*/

class JavaNode {
    int modifier;
    String type; // extends for classes
    String name; // full name for classes
    String[] implement; // implements list
    int fieldCount;
    JavaNode field;  // field list
    JavaNode method; // method list for classes
    JavaSource source;
}

class JavaSource implements Opcodes {
    private static final String[] CHAR_SPOOL = new String[128];
    private static final Map MODS = new HashMap();
    private int p, e;
    private char[] s;
    private String lookahead;
    private Map classes;
    private int line;
    final String packageName;
    final List imports = new ArrayList();

    private String get(boolean ignore) {
        if (lookahead != null) {
            String id = lookahead;
            lookahead = null;
            return id;
        }
        char c, s[] = this.s;
        int p = this.p - 1, e = this.e;
    skip:
        for (;;) {
            // skip whitespace and comments
            while (++p < e && (c = s[p]) >= '\000' && c <= ' ');
            if (p + 1 < e && s[p] == '/') {
                if ((c = s[++p]) == '/') {
                    while (++p < e && (c = s[p]) != '\r' && c != '\n');
                    continue skip;
                }
                if (c == '*') {
                    for (++p; ++p < e && s[p - 1] != '*' || s[p] != '/';)
                    continue skip;
                }
                --p;
            }
            break;
        }
        if (p >= e) {
            this.p = p;
            return null;
        }
        int f = p;
        // get token
        while (p < e && ((c = s[p]) == '_' || c >= 'a' && c <= 'z' ||
              c >= 'A' && c <= 'Z' || c >= '0' && c <= '9' || c > '~')) ++p;
        this.p = f == p ? ++p : p;
        int l;
        // faster and ensures all operators to be interned
        if ((l = p - f) == 1 && (c = s[f]) >= '\000' && c < CHAR_SPOOL.length)
            return CHAR_SPOOL[c];
        return ignore ? "" : new String(s, f, p - f);
    }

    void expect(String expect, String id) {
        if (!expect.equals(id))
            throw new CompileException(line, 0,
                        "Expected `" + id + "', got `" + id + '\'');
    }

    private String cname() {
        StringBuffer result = null;
        String id = get(false), sep = null;
        while (id != null && (sep = get(false)) == ".") {
            if (result == null)
                result = new StringBuffer(id);
            result.append('/');
            if ((id = get(false)) != null)
                result.append(id);
        }
        lookahead = sep;
        return result == null ? id : result.toString();
    }

    String type(boolean dotted) {
        String type = cname();
        //
        return type;
    }

    String skipExpr(boolean method) {
        //
        return null;
    }

    int modifiers() {
        String id;
        Object mod;
        int result = 0;
        while ((mod = MODS.get(id = get(false))) != null) {
            result |= ((Integer) mod).intValue();
        }
        lookahead = id;
        return result;
    }

    private String param(int modifiers, String type, JavaNode target) {
        JavaNode n = new JavaNode();
        n.modifier = modifiers;
        n.type = type == null ? type(true) : type;
        String id = type(false);
        while (id.endsWith("[]")) {
            n.type += "[]";
            id = id.substring(0, id.length() - 2);
        }
        id = get(false);
        if (id != "(" || type == null) {
            n.field = target.field;
            target.field = n;
            return id != "=" ? id : skipExpr(true);
        }
        while ((id = param(modifiers(), null, n)) == ",");
        expect(")", id);
        n.method = target.method;
        target.method = n;
        return skipExpr(false);
    }

    private String readClass(String outer, int modifiers) {
        String id = type(true);
        if ("interface".equals(id))
            modifiers |= ACC_INTERFACE;
        else if (!"class".equals(id))
            return id;
        JavaNode cl = new JavaNode();
        cl.source = this;
        cl.modifier = modifiers;
        cl.name = get(false);
        if (outer != null)
            cl.name = outer + '$' + cl.name;
        id = get(false);
        if ("extends".equals(id)) {
            cl.type = cname();
            id = get(false);
        }
        if ("implements".equals(id)) {
            List impl = new ArrayList();
            do {
                impl.add(cname());
            } while ((id = get(true)) == ",");
            cl.implement = (String[]) impl.toArray(new String[impl.size()]);
        }
        expect("{", id);
        while ((id = readClass(cl.name, modifiers = modifiers())) != null) {
            while (id != "" && param(modifiers, id, cl) == ",");
        }
        classes.put(packageName.length() == 0 ? cl.name :
                        packageName + '/' + cl.name, cl);
        return "";
    }

    JavaSource(char[] source, Map classes) {
        s = source;
        e = source.length;
        this.classes = classes;
        String id = get(false);
        if ("package".equals(id)) {
            packageName = get(false);
            id = get(false);
        } else {
            packageName = "";
        }
        for (; id != null; id = get(false)) {
            if (id == ";")
                continue; // skip toplevel ;
            if (!"import".equals(id))
                break;
            if ((id = cname()) != null)
                imports.add(id);
        }
        lookahead = id;
        while (readClass(null, modifiers()) != null);
        s = null;
    }

    private static void mod(String name, int value) {
        MODS.put(name, new Integer(value));
    }

    static {
        char[] x = { ' ' };
        for (short i = 0; i < CHAR_SPOOL.length; ++i) {
            x[0] = (char) i;
            CHAR_SPOOL[i] = new String(x).intern();
        }
        mod("abstract", ACC_ABSTRACT);
        mod("final", ACC_FINAL);
        mod("native", ACC_NATIVE);
        mod("private", ACC_PRIVATE);
        mod("protected", ACC_PROTECTED);
        mod("public", ACC_PUBLIC);
        mod("static", ACC_STATIC);
        mod("strictfp", ACC_STRICT);
        mod("synchronized", ACC_SYNCHRONIZED);
        mod("transient", ACC_TRANSIENT);
        mod("volatile", ACC_VOLATILE);
    }
}