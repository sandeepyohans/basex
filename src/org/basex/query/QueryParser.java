package org.basex.query;

import static org.basex.query.QueryTokens.*;
import static org.basex.query.QueryText.*;
import static org.basex.util.Token.*;
import java.io.IOException;
import org.basex.io.IO;
import org.basex.query.expr.And;
import org.basex.query.expr.CAttr;
import org.basex.query.expr.CComm;
import org.basex.query.expr.CDoc;
import org.basex.query.expr.CElem;
import org.basex.query.expr.CPI;
import org.basex.query.expr.CText;
import org.basex.query.expr.Calc;
import org.basex.query.expr.Case;
import org.basex.query.expr.Cast;
import org.basex.query.expr.Castable;
import org.basex.query.expr.Catch;
import org.basex.query.expr.Clc;
import org.basex.query.expr.CmpG;
import org.basex.query.expr.CmpN;
import org.basex.query.expr.CmpV;
import org.basex.query.expr.Context;
import org.basex.query.expr.Except;
import org.basex.query.expr.Expr;
import org.basex.query.expr.FLWOR;
import org.basex.query.expr.FLWR;
import org.basex.query.expr.For;
import org.basex.query.expr.ForLet;
import org.basex.query.expr.Func;
import org.basex.query.expr.If;
import org.basex.query.expr.Instance;
import org.basex.query.expr.InterSect;
import org.basex.query.expr.Let;
import org.basex.query.expr.List;
import org.basex.query.expr.Or;
import org.basex.query.expr.Ord;
import org.basex.query.expr.Order;
import org.basex.query.expr.Pred;
import org.basex.query.expr.Range;
import org.basex.query.expr.Root;
import org.basex.query.expr.Satisfy;
import org.basex.query.expr.Treat;
import org.basex.query.expr.Try;
import org.basex.query.expr.TypeSwitch;
import org.basex.query.expr.Unary;
import org.basex.query.expr.Union;
import org.basex.query.expr.VarCall;
import org.basex.query.ft.FTAnd;
import org.basex.query.ft.FTContains;
import org.basex.query.ft.FTExpr;
import org.basex.query.ft.FTMildNot;
import org.basex.query.ft.FTNot;
import org.basex.query.ft.FTOpt;
import org.basex.query.ft.FTOptions;
import org.basex.query.ft.FTOr;
import org.basex.query.ft.FTPos;
import org.basex.query.ft.FTWords;
import org.basex.query.ft.FTOpt.FTMode;
import org.basex.query.ft.FTPos.FTUnit;
import org.basex.query.item.Dbl;
import org.basex.query.item.Dec;
import org.basex.query.item.Itr;
import org.basex.query.item.QNm;
import org.basex.query.item.Seq;
import org.basex.query.item.SeqType;
import org.basex.query.item.Str;
import org.basex.query.item.Type;
import org.basex.query.item.Uri;
import org.basex.query.path.Axis;
import org.basex.query.path.AxisPath;
import org.basex.query.path.KindTest;
import org.basex.query.path.MixedPath;
import org.basex.query.path.NameTest;
import org.basex.query.path.Step;
import org.basex.query.path.Test;
import org.basex.query.util.Err;
import org.basex.query.util.NSLocal;
import org.basex.query.util.Var;
import org.basex.util.Array;
import org.basex.util.Atts;
import org.basex.util.InputParser;
import org.basex.util.Set;
import org.basex.util.TokenBuilder;
import org.basex.util.TokenList;
import org.basex.util.XMLToken;

/**
 * Simple query parser; can be overwritten to support more complex parsings.
 *
 * @author Workgroup DBIS, University of Konstanz 2005-08, ISC License
 * @author Christian Gruen
 */
public class QueryParser extends InputParser {
  /** Resulting XQuery expression. */
  private final QueryContext ctx;
  /** Temporary token builder. */
  private final TokenBuilder tok = new TokenBuilder();

  /** List of loaded modules. */
  public TokenList modLoaded = new TokenList();
  /** Module name. */
  private QNm module;

  /** Alternative error output. */
  private Object[] alter;
  /** Alternative position. */
  private int ap;

  /** Declaration flag. */
  private boolean declElem;
  /** Declaration flag. */
  private boolean declFunc;
  /** Declaration flag. */
  private boolean declColl;
  /** Declaration flag. */
  private boolean declConstr;
  /** Declaration flag. */
  private boolean declSpaces;
  /** Declaration flag. */
  private boolean declOrder;
  /** Declaration flag. */
  private boolean declReval;
  /** Declaration flag. */
  private boolean declGreat;
  /** Declaration flag. */
  private boolean declPres;
  /** Declaration flag. */
  private boolean declBase;

  /***
   * Constructor.
   * @param c query context
   */
  public QueryParser(final QueryContext c) {
    ctx = c;
  }

  /**
   * Parses the specified query.
   * If <code>uri != null</code>, the query is treated as a module
   * @param q input query
   * @param f optional input file
   * @param u module uri
   * @return resulting expression
   * @throws QueryException xquery exception
   */
  Expr parse(final String q, final IO f, final Uri u) throws QueryException {
    init(q);
    file = f;
    if(!more()) Err.or(QUERYEMPTY);
    final int v = valid();
    if(v != -1) Err.or(QUERYINV, v);
    return parse(u, true);
  }

  /**
   * Parses the specified query.
   * If <code>uri != null</code>, the query is treated as a module
   * [  1] Parses a Module.
   * @param u module uri
   * @param end if true, input must be completely evaluated
   * @return resulting expression
   * @throws QueryException xquery exception
   */
  public Expr parse(final Uri u, final boolean end) throws QueryException {
    try {
      versionDecl();
      Expr ex = null;
      if(u == null) {
        ex = mainModule();
        if(ex == null) if(alter != null) error(); else Err.or(EXPREMPTY);
      } else {
        moduleDecl(u);
      }

      if(end && more()) {
        if(alter != null) error();
        Err.or(QUERYEND, rest());
      }
      ctx.fun.check();
      ctx.ns.finish(ctx.nsElem);
      return ex;
    } catch(final QueryException ex) {
      mark();
      ex.pos(this);
      throw ex;
    }
  }

  /**
   * [  2] Parses a VersionDecl.
   * @throws QueryException xquery exception
   */
  private void versionDecl() throws QueryException {
    final int p = qp;
    if(!consumeWS(XQUERY) || !consumeWS2(VERSION)) {
      qp = p;
      return;
    }
    final String ver = string(stringLiteral());

    final byte[] enc = consumeWS2(ENCODING) ? lc(stringLiteral()) : null;
    if(enc != null) {
      boolean v = true;
      for(final byte e : enc)
        v &= letterOrDigit(e) || e == '-';
      if(!v) Err.or(XQUERYENC2, enc);
    }
    ctx.encoding = enc;
    skipWS();
    check(';');
    if(!ver.equals(ONEZERO)) Err.or(XQUERYVER, ver);
  }

  /**
   * [  3] Parses a MainModule.
   * [  7] Parses a Setter.
   * [ 30] Parses a QueryBody ( = Expr).
   * @return query expression
   * @throws QueryException xquery exception
   */
  private Expr mainModule() throws QueryException {
    prolog1();
    prolog2();
    if(declColl) {
      final byte[] coll = ctx.baseURI.resolve(ctx.collation).str();
      if(!eq(URLCOLL, coll)) Err.or(COLLWHICH, coll);
    }
    return expr();
  }

  /**
   * [  4] Parses a ModuleDecl.
   * @param u module uri
   * @throws QueryException xquery exception
   */
  private void moduleDecl(final Uri u) throws QueryException {
    check(MODULE);
    check(NSPACE);
    module = new QNm(ncName(XPNAME));
    check(IS);
    module.uri = Uri.uri(stringLiteral());
    if(module.uri == Uri.EMPTY) Err.or(NSMODURI);
    // skip uri check for empty input uri...
    if(u != Uri.EMPTY && !u.eq(module.uri))
      Err.or(WRONGMODULE, module.uri, file);
    ctx.ns.add(module);
    skipWS();
    check(';');
    prolog1();
    prolog2();
  }

  /**
   * [  6] Parses a Prolog.
   * [  7] Parses a Setter.
   * @throws QueryException xquery exception
   */
  private void prolog1() throws QueryException {
    while(true) {
      final int p = qp;
      if(consumeWS(DECLARE)) {
        if(consumeWS(DEFAULT)) {
          if(!defaultNamespaceDecl() && !defaultCollationDecl() &&
              !emptyOrderDecl()) Err.or(list(ELEMENT, COLLATION, EMPTYORDER),
                  DECLINCOMPLETE);
        } else if(consumeWS(BOUNDARY)) {
          boundarySpaceDecl();
        } else if(consumeWS(BASEURI)) {
          baseURIDecl();
        } else if(consumeWS(CONSTRUCTION)) {
          constructionDecl();
        } else if(consumeWS(ORDERING)) {
          orderingModeDecl();
        } else if(consumeWS(REVALIDATION)) {
          revalidationDecl();
        } else if(consumeWS(COPYNS)) {
          copyNamespacesDecl();
        } else if(consumeWS(NSPACE)) {
          namespaceDecl();
        } else {
          qp = p;
          return;
        }
      } else if(consumeWS(IMPORT)) {
        if(consumeWS(SCHEMA)) {
          schemaImport();
        } else if(consumeWS(MODULE)) {
          moduleImport();
        } else {
          qp = p;
          return;
        }
      } else {
        return;
      }
      skipWS();
      check(';');
    }
  }

  /**
   * [  6] Parses a Prolog.
   * [  7] Parses a Setter.
   * @throws QueryException xquery exception
   */
  private void prolog2() throws QueryException {
    while(true) {
      final int p = qp;
      if(!consumeWS(DECLARE)) return;

      if(consumeWS(VARIABLE)) {
        varDecl();
      } else if(consumeWS(FUNCTION)) {
        functionDecl();
      } else if(consumeWS(OPTION)) {
        optionDecl();
      } else if(consumeWS(FTOPTION)) {
        ftMatchOption(ctx.ftopt);
      } else {
        qp = p;
        return;
      }
      skipWS();
      check(';');
    }
  }

  /**
   * [ 10] Parses a namespaceDecl.
   * @throws QueryException xquery exception
   */
  private void namespaceDecl() throws QueryException {
    final QNm name = new QNm(ncName(XPNAME));
    check(IS);
    name.uri = Uri.uri(stringLiteral());
    if(ctx.ns.find(name.ln()) != null) Err.or(DUPLNSDECL, name);
    ctx.ns.add(name);
  }

  /**
   * [UP141] Parses a RevalidationDecl.
   * @throws QueryException xquery exception
   */
  private void revalidationDecl() throws QueryException {
    if(declReval) Err.or(DUPLREVAL);
    ctx.revalidate = consumeWS2(STRICT) ? 0 : consumeWS2(LAX) ? 1 :
        consumeWS2(SKIP) ? 2 : -1;
    Err.or(UPIMPL);
  }

  /**
   * [ 11] Parses a BoundarySpaceDecl.
   * @throws QueryException xquery exception
   */
  private void boundarySpaceDecl() throws QueryException {
    if(declSpaces) Err.or(DUPLBOUND);
    final boolean spaces = consumeWS2(PRESERVE);
    if(!spaces) check(STRIP);
    ctx.spaces = spaces;
    declSpaces = true;
  }

  /**
   * [ 12] Parses a DefaultNamespaceDecl.
   * @return true if declaration was found
   * @throws QueryException xquery exception
   */
  private boolean defaultNamespaceDecl() throws QueryException {
    final boolean elem = consumeWS(ELEMENT);
    if(!elem && !consumeWS(FUNCTION)) return false;
    check(NSPACE);
    final byte[] ns = stringLiteral();
    if(elem) {
      if(declElem) Err.or(DUPLNS);
      ctx.nsElem = ns;
      declElem = true;
    } else {
      if(declFunc) Err.or(DUPLNS);
      ctx.nsFunc = ns;
      declFunc = true;
    }
    return true;
  }

  /**
   * [ 13] Parses an OptionDecl.
   * @throws QueryException xquery exception
   */
  private void optionDecl() throws QueryException {
    // ignore option declarations
    final QNm name = new QNm(qName(QNAMEINV), ctx);
    stringLiteral();
    if(!name.ns()) Err.or(NSMISS, name);
  }

  /**
   * [ 14] Parses an OrderingModeDecl.
   * @throws QueryException xquery exception
   */
  private void orderingModeDecl() throws QueryException {
    if(declOrder) Err.or(DUPLORD);
    final boolean ordered = consumeWS2(ORDERED);
    if(!ordered) check(UNORDERED);
    ctx.ordered = ordered;
    declOrder = true;
  }

  /**
   * [ 15] Parses an emptyOrderDecl.
   * @return true if declaration was found
   * @throws QueryException xquery exception
   */
  private boolean emptyOrderDecl() throws QueryException {
    if(!consumeWS2(EMPTYORDER)) return false;
    check(EMPTYORD);
    if(declGreat) Err.or(DUPLORDEMP);
    final boolean order = consumeWS2(GREATEST);
    if(!order) check(LEAST);
    ctx.orderGreatest = order;
    declGreat = true;
    return true;
  }

  /**
   * [ 16] Parses a copyNamespacesDecl.
   * [ 17] Parses an PreserveMode.
   * [ 18] Parses an InheritMode.
   * @throws QueryException xquery exception
   */
  private void copyNamespacesDecl() throws QueryException {
    if(declPres) Err.or(DUPLCOPYNS);
    boolean nsp = consumeWS(PRESERVE);
    if(!nsp) check(NOPRESERVE);
    ctx.nsPreserve = nsp;
    declPres = true;
    consume(',');
    nsp = consumeWS(INHERIT);
    if(!nsp) check(NOINHERIT);
    ctx.nsInherit = nsp;
  }

  /**
   * [ 19] Parses a DefaultCollationDecl.
   * @return query expression
   * @throws QueryException xquery exception
   */
  private boolean defaultCollationDecl() throws QueryException {
    if(!consumeWS2(COLLATION)) return false;
    ctx.collation = Uri.uri(stringLiteral());
    if(declColl) Err.or(DUPLCOLL);
    declColl = true;
    return true;
  }

  /**
   * [ 20] Parses a BaseURIDecl.
   * @throws QueryException xquery exception
   */
  private void baseURIDecl() throws QueryException {
    if(declBase) Err.or(DUPLBASE);
    ctx.baseURI = Uri.uri(stringLiteral());
    declBase = true;
  }

  /**
   * [ 21] Parses a SchemaImport.
   * [ 22] Parses a SchemaPrefix.
   * @throws QueryException xquery exception
   */
  private void schemaImport() throws QueryException {
    if(consumeWS(NSPACE)) {
      ncName(XPNAME);
      check(IS);
    } else if(consumeWS(DEFAULT)) {
      check(ELEMENT);
      check(NSPACE);
    }
    final byte[] ns = stringLiteral();
    if(ns.length == 0) Err.or(NSEMPTY);
    if(consumeWS(AT)) do stringLiteral(); while(consumeWS(COMMA));
    Err.or(IMPLSCHEMA);
  }

  /**
   * [ 23] Parses a ModuleImport.
   * @throws QueryException xquery exception
   */
  private void moduleImport() throws QueryException {
    QNm name = null;
    if(consumeWS(NSPACE)) {
      name = new QNm(ncName(XPNAME));
      check(IS);
    } else {
      name = new QNm();
    }
    final byte[] uri = stringLiteral();
    if(uri.length == 0) Err.or(NSMODURI);
    name.uri = Uri.uri(uri);
    ctx.ns.add(name);

    final TokenList fl = new TokenList();
    if(consumeWS(AT)) do fl.add(stringLiteral()); while(consumeWS(COMMA));

    if(modLoaded.contains(uri)) Err.or(DUPLMODULE, name.uri);
    try {
      if(fl.size == 0) {
        boolean found = false;
        for(int n = 0; n < ctx.modules.size; n += 2) {
          if(ctx.modules.list[n].equals(string(uri))) {
            module(ctx.modules.list[n + 1], name.uri);
            modLoaded.add(uri);
            found = true;
          }
        }
        if(!found) Err.or(NOMODULE, uri);
      }
      for(int n = 0; n < fl.size; n++) {
        module(string(fl.list[n]), name.uri);
        modLoaded.add(uri);
      }
    } catch(final StackOverflowError ex) {
      Err.or(CIRCMODULE);
    }
  }

  /**
   * Parses the specified module.
   * @param f file name
   * @param u module uri
   * @throws QueryException xquery exception
   */
  private void module(final String f, final Uri u) throws QueryException {
    if(ctx.modLoaded.contains(f)) return;
    // check specified path and path relative to query file
    IO fl = IO.get(f);
    if(!fl.exists() && file != null) fl = file.merge(fl);

    String query = null;
    try {
      query = string(fl.content());
    } catch(final IOException ex) {
      Err.or(NOMODULEFILE, fl);
    }

    final NSLocal ns = ctx.ns;
    ctx.ns = new NSLocal();
    new QueryParser(ctx).parse(query, fl, u);
    ctx.ns = ns;
    ctx.modLoaded.add(f);
  }

  /**
   * [ 24] Parses a VarDecl.
   * @throws QueryException xquery exception
   */
  private void varDecl() throws QueryException {
    final QNm name = varName();
    if(module != null && !name.uri.eq(module.uri)) Err.or(MODNS, name);

    final SeqType type = consumeWS(AS) ? sequenceType() : null;
    final Var var = new Var(name, type);
    final Var ext = ctx.vars.get(var);

    if(consumeWS2(EXTERNAL)) {
      if(ext == null) {
        ctx.vars.addGlobal(var);
      } else if(type != null) {
        // variable has been bound before parsing the query
        ext.type = type;
        ext.item = ext.check(ext.item, ctx);
      }
    } else {
      if(ext != null) Err.or(VARDEFINE, var);
      check(ASSIGN);
      ctx.vars.addGlobal(var.bind(check(single(), VARMISSING), ctx));
    }
  }

  /**
   * [ 25] Parses a ConstructionDecl.
   * @throws QueryException xquery exception
   */
  private void constructionDecl() throws QueryException {
    if(declConstr) Err.or(DUPLCONS);
    final boolean cons = consumeWS2(PRESERVE);
    if(!cons) check(STRIP);
    ctx.construct = cons;
    declConstr = true;
  }

  /**
   * [ 26] Parses a FunctionDecl.
   * @throws QueryException xquery exception
   */
  private void functionDecl() throws QueryException {
    final QNm name = new QNm(qName(DECLFUNC));
    name.uri = Uri.uri(name.ns() ? ctx.ns.uri(name.pre()) : ctx.nsFunc);

    if(name.pre().length == 0 && Type.find(name, true) != null)
      Err.or(FUNCRES, name);
    if(module != null && !name.uri.eq(module.uri)) Err.or(MODNS, name);

    check(PAR1);
    skipWS();
    Var[] args = {};
    final int s = ctx.vars.size();
    while(curr() == '$') {
      final QNm arg = varName();
      final SeqType argType = consumeWS(AS) ? sequenceType() : null;
      final Var var = new Var(arg, argType);
      ctx.vars.add(var);

      for(final Var v : args) if(v.name.eq(arg)) Err.or(FUNCDUPL, arg);

      args = Array.add(args, var);
      if(!consume(',')) break;
      skipWS();
    }
    check(PAR2);

    final SeqType type = consumeWS(AS) ? sequenceType() : null;
    final Func func = new Func(new Var(name, type), args, true);

    ctx.fun.add(func);
    if(!consumeWS(EXTERNAL)) func.expr = enclosed(NOFUNBODY);
    ctx.vars.reset(s);
  }

  /**
   * [ 29] Parses an EnclosedExpr.
   * @param err error message
   * @return query expression
   * @throws QueryException xquery exception
   */
  private Expr enclosed(final Object[] err) throws QueryException {
    check(BRACE1);
    final Expr e = check(expr(), err);
    check(BRACE2);
    return e;
  }

  /**
   * [ 31] Parses an Expr.
   * @return query expression
   * @throws QueryException xquery exception
   */
  private Expr expr() throws QueryException {
    final Expr e = single();
    if(e == null) {
      if(more()) return null;
      if(alter != null) error(); else Err.or(NOEXPR);
    }

    if(!consumeWS2(COMMA)) return e;
    Expr[] list = { e };
    do list = add(list, single()); while(consumeWS2(COMMA));
    return new List(list);
  }

  /**
   * [ 32] Parses an ExprSingle.
   * @return query expression
   * @throws QueryException xquery exception
   */
  private Expr single() throws QueryException {
    alter = null;
    Expr e = flwor();
    if(e == null) e = quantified();
    if(e == null) e = typeswitch();
    if(e == null) e = iff();
    if(e == null) e = tryCatch();
    if(e == null) e = deletee();
    if(e == null) e = or();
    return e;
  }

  /**
   * [ 33] Parses a FLWORExpr.
   * [ 37] Parses a WhereClause.
   * [ 38] Parses an OrderByClause.
   * [ 39] Parses an OrderSpecList.
   * @return query expression
   * @throws QueryException xquery exception
   */
  private Expr flwor() throws QueryException {
    final int s = ctx.vars.size();

    final ForLet[] fl = forLet();
    if(fl == null) return null;

    Expr where = null;
    if(consumeWS(WHERE)) {
      ap = qp;
      where = check(single(), NOWHERE);
      alter = NOWHERE;
    }

    Ord[] order = null;
    final boolean stable = consumeWS(STABLE);
    if(stable) check(EMPTYORDER);

    if(stable || consumeWS(EMPTYORDER)) {
      check(BY);
      ap = qp;
      do order = orderSpec(order); while(consumeWS2(COMMA));
      if(order != null) order = Array.add(order, new Ord());
      alter = ORDERBY;
    }

    if(!consumeWS(RETURN)) {
      if(alter != null) error();
      Err.or(where == null ? FLWORWHERE : order == null ? FLWORORD : FLWORRET);
    }
    final Expr ret = check(single(), NORETURN);
    ctx.vars.reset(s);

    return order == null ? new FLWR(fl, where, ret) :
      new FLWOR(fl, where, new Order(order), ret);
  }

  /**
   * [ 34] Parses a ForClause.
   * [ 35] Parses a PositionalVar.
   * [ 36] Parses a LetClause.
   * [FT37] Parses an FTScoreVar.
   * @return query expression
   * @throws QueryException xquery exception
   */
  private ForLet[] forLet() throws QueryException {
    ForLet[] fl = null;
    boolean comma = false;

    do {
      final boolean fr = consumeWS(FOR, DOLLAR, NOFOR);
      boolean score = !fr && consumeWS(LET, SCORE, NOLET);
      if(score) check(SCORE);
      else if(!fr && !consumeWS(LET, DOLLAR, NOLET)) return fl;

      do {
        if(comma && !fr) score = consumeWS(SCORE);

        final QNm name = varName();
        final SeqType type = !score && consumeWS(AS) ? sequenceType() : null;
        final Var var = new Var(name, type);

        final Var at = fr && consumeWS(AT) ? new Var(varName()) : null;
        final Var sc = fr && consumeWS(SCORE) ? new Var(varName()) : null;

        check(fr ? IN : ASSIGN);
        final Expr e = check(single(), VARMISSING);
        ctx.vars.add(var);

        if(fl == null) fl = new ForLet[1];
        else fl = Array.resize(fl, fl.length, fl.length + 1);
        if(sc != null) {
          if(sc.name.eq(name) || at != null && sc.name.eq(at.name))
            Err.or(VARDEFINED, sc);
          ctx.vars.add(sc);
        }
        if(at != null) {
          if(name.eq(at.name)) Err.or(VARDEFINED, at);
          ctx.vars.add(at);
        }
        fl[fl.length - 1] = fr ? new For(e, var, at, sc) :
          new Let(e, var, score);
        score = false;
        comma = true;
      } while(consumeWS2(COMMA));
      comma = false;
    } while(true);
  }

  /**
   * [ 40] Parses an OrderSpec.
   * [ 41] Parses an OrderModifier.
   * @param order order array
   * @return new order array
   * @throws QueryException xquery exception
   */
  private Ord[] orderSpec(final Ord[] order) throws QueryException {
    final Expr e = check(single(), ORDERBY);
    boolean desc = false;
    if(!consumeWS(ASCENDING)) desc = consumeWS(DESCENDING);
    boolean least = !ctx.orderGreatest;
    if(consumeWS(EMPTYORD)) {
      least = !consumeWS(GREATEST);
      if(least) check(LEAST);
    }
    if(consumeWS(COLLATION)) {
      final byte[] coll = stringLiteral();
      if(!eq(URLCOLL, coll)) Err.or(INVCOLL, coll);
    }
    if(e.e()) return order;
    final Ord ord = new Ord(e, desc, least);
    return order == null ? new Ord[] { ord } : Array.add(order, ord);
  }

  /**
   * [ 42] Parses a QuantifiedExpr.
   * @return query expression
   * @throws QueryException xquery exception
   */
  private Expr quantified() throws QueryException {
    final boolean some = consumeWS(SOME, DOLLAR, NOSOME);
    if(!some && !consumeWS(EVERY, DOLLAR, NOSOME)) return null;

    final int s = ctx.vars.size();
    For[] fl = {};
    do {
      final Var var = new Var(varName(), consumeWS(AS) ? sequenceType() : null);
      check(IN);
      final Expr e = check(single(), NOSOME);
      ctx.vars.add(var);
      fl = Array.add(fl, new For(e, var, null, null));
    } while(consumeWS2(COMMA));

    check(SATISFIES);
    final Expr e = check(single(), NOSOME);
    ctx.vars.reset(s);
    return new Satisfy(fl, e, !some);
  }

  /**
   * [ 42] Parses a TypeswitchExpr.
   * @return query expression
   * @throws QueryException xquery exception
   */
  private Expr typeswitch() throws QueryException {
    if(!consumeWS(TYPESWITCH, PAR1, TYPEPAR)) return null;

    check(PAR1);
    final Expr e = check(expr(), NOTYPESWITCH);
    check(PAR2);

    Case[] cases = {};
    while(consumeWS(CASE)) {
      final int s = ctx.vars.size();
      skipWS();
      QNm name = null;
      if(curr() == '$') {
        name = varName();
        check(AS);
      }
      final Var var = new Var(name, sequenceType());
      if(name != null) ctx.vars.add(var);
      check(RETURN);
      final Expr ret = check(single(), NOTYPESWITCH);
      cases = Array.add(cases, new Case(var, ret));
      ctx.vars.reset(s);
    }
    if(cases.length == 0) Err.or(NOTYPESWITCH);

    check(DEFAULT);
    skipWS();
    final int s = ctx.vars.size();
    final QNm name = curr() == '$' ? varName() : null;
    final Var var = name != null ? new Var(name, null) : null;
    if(var != null) ctx.vars.add(var);
    check(RETURN);

    final Expr ret = check(single(), NOTYPESWITCH);
    ctx.vars.reset(s);
    return new TypeSwitch(e, cases, var, ret);
  }

  /**
   * [ 45] Parses an IfExpr.
   * @return query expression
   * @throws QueryException xquery exception
   */
  private Expr iff() throws QueryException {
    if(!consumeWS(IF, PAR1, IFPAR)) return null;
    check(PAR1);
    final Expr e = check(expr(), NOIF);
    check(PAR2);
    if(!consumeWS(THEN)) Err.or(NOIF);
    final Expr thn = check(single(), NOIF);
    if(!consumeWS(ELSE)) Err.or(NOIF);
    final Expr els = check(single(), NOIF);
    return new If(e, thn, els);
  }

  /**
   * [ 46] Parses an OrExpr.
   * @return query expression
   * @throws QueryException xquery exception
   */
  private Expr or() throws QueryException {
    final Expr e = and();
    if(!consumeWS(OR)) return e;

    Expr[] list = { e };
    do list = add(list, and()); while(consumeWS(OR));
    return new Or(list);
  }

  /**
   * [ 47] Parses an AndExpr.
   * @return query expression
   * @throws QueryException xquery exception
   */
  private Expr and() throws QueryException {
    final Expr e = comparison();
    if(!consumeWS(AND)) return e;

    Expr[] list = { e };
    do list = add(list, comparison()); while(consumeWS(AND));
    return new And(list);
  }

  /**
   * [ 48] Parses an ComparisonExpr.
   * @return query expression
   * @throws QueryException xquery exception
   */
  private Expr comparison() throws QueryException {
    final Expr e = ftContains();
    if(e == null) return null;

    for(final CmpV.Comp c : CmpV.Comp.values()) if(consumeWS(c.name))
        return new CmpV(e, check(ftContains(), CMPEXPR), c);
    for(final CmpN.Comp c : CmpN.Comp.values()) if(consumeWS(c.name))
        return new CmpN(e, check(ftContains(), CMPEXPR), c);
    for(final CmpG.Comp c : CmpG.Comp.values()) if(consumeWS2(c.name))
        return new CmpG(e, check(ftContains(), CMPEXPR), c);

    return e;
  }

  /**
   * [FT51] Parses a FTContainsExpr.
   * @return query expression
   * @throws QueryException xquery exception
   */
  private Expr ftContains() throws QueryException {
    final Expr e = range();
    if(!consumeWS(FTCONTAINS)) return e;

    // [CG] XQuery/FTIgnoreOption
    final FTExpr select = ftSelection();
    //Expr ignore = null;
    if(consumeWS2(WITHOUT) && consumeWS2(CONTENT)) {
      //ignore = union();
      union();
      Err.or(FTIGNORE);
    }
    return new FTContains(e, select);
  }

  /**
   * [ 49] Parses a RangeExpr.
   * @return query expression
   * @throws QueryException xquery exception
   */
  private Expr range() throws QueryException {
    final Expr e = additive();
    if(!consumeWS(TO)) return e;
    return new Range(e, check(additive(), INCOMPLETE));
  }

  /**
   * [ 50] Parses an AdditiveExpr.
   * @return query expression
   * @throws QueryException xquery exception
   */
  private Expr additive() throws QueryException {
    Expr e = multiplicative();

    while(true) {
      final Calc c = consume('+') ? Calc.PLUS : consume('-') ?
          Calc.MINUS : null;
      if(c == null) break;
      e = new Clc(e, check(multiplicative(), CALCEXPR), c);
    }
    return e;
  }

  /**
   * [ 51] Parses a MultiplicativeExpr.
   * @return query expression
   * @throws QueryException xquery exception
   */
  private Expr multiplicative() throws QueryException {
    Expr e = union();
    if(e == null) return null;

    while(true) {
      final Calc c = consume('*') ? Calc.MULT : consumeWS(DIV) ?
        Calc.DIV : consumeWS(IDIV) ? Calc.IDIV : consumeWS(MOD) ?
        Calc.MOD : null;
      if(c == null) break;
      e = new Clc(e, check(union(), CALCEXPR), c);
    }
    return e;
  }

  /**
   * [ 52] Parses a UnionExpr.
   * @return query expression
   * @throws QueryException xquery exception
   */
  private Expr union() throws QueryException {
    final Expr e = intersect();
    if(!consumeWS(UNION) && !consumeWS2(PIPE)) return e;

    Expr[] list = { e };
    do list = add(list, intersect());
    while(consumeWS(UNION) || consumeWS2(PIPE));
    return new Union(list);
  }

  /**
   * [ 52] Parses an IntersectExceptExpr.
   * @return query expression
   * @throws QueryException xquery exception
   */
  private Expr intersect() throws QueryException {
    final Expr e = instanceoff();

    if(consumeWS(INTERSECT)) {
      Expr[] list = { e };
      do list = add(list, instanceoff()); while(consumeWS(INTERSECT));
      return new InterSect(list);
    } else if(consumeWS(EXCEPT)) {
      Expr[] list = { e };
      do list = add(list, instanceoff()); while(consumeWS(EXCEPT));
      return new Except(list);
    } else {
      return e;
    }
  }

  /**
   * [ 54] Parses an InstanceofExpr.
   * @return query expression
   * @throws QueryException xquery exception
   */
  private Expr instanceoff() throws QueryException {
    final Expr e = treat();
    if(!consumeWS(INSTANCE)) return e;
    check(OF);
    return new Instance(e, sequenceType());
  }

  /**
   * [ 55] Parses a TreatExpr.
   * @return query expression
   * @throws QueryException xquery exception
   */
  private Expr treat() throws QueryException {
    final Expr e = castable();
    if(!consumeWS(TREAT)) return e;
    check(AS);
    return new Treat(e, sequenceType());
  }

  /**
   * [ 56] Parses a CastableExpr.
   * @return query expression
   * @throws QueryException xquery exception
   */
  private Expr castable() throws QueryException {
    final Expr e = cast();
    if(!consumeWS(CASTABLE)) return e;
    check(AS);
    return new Castable(e, simpleType());
  }

  /**
   * [ 57] Parses a CastExpr.
   * @return query expression
   * @throws QueryException xquery exception
   */
  private Expr cast() throws QueryException {
    final Expr e = unary();
    if(!consumeWS(CAST)) return e;
    check(AS);
    return new Cast(e, simpleType());
  }

  /**
   * [ 58] Parses a UnaryExpr.
   * @return query expression
   * @throws QueryException xquery exception
   */
  private Expr unary() throws QueryException {
    boolean minus = false;
    boolean found = false;
    do {
      skipWS();
      if(consume('-')) {
        minus ^= true;
        found = true;
      } else if(consume('+')) {
        found = true;
      } else {
        final Expr e = value();
        return found ? new Unary(check(e, EVALUNARY), minus) : e;
      }
    } while(true);
  }

  /**
   * [ 59] Parses a ValueExpr.
   * [ 65] Parses an ExtensionExpr.
   * @return query expression
   * @throws QueryException xquery exception
   */
  private Expr value() throws QueryException {
    final boolean lax = consumeWS(VALIDATE, LAX, NOVALIDATE);
    if(lax || consumeWS(VALIDATE, STRICT, NOVALIDATE)) validate();

    final Expr e = path();
    return e != null ? e : pragma() ? enclosed(NOPRAGMA) : null;
  }

  /**
   * [ 63] Parses a ValidateExpr.
   * @throws QueryException xquery exception
   */
  private void validate() throws QueryException {
    if(!consumeWS2(LAX)) consumeWS2(STRICT);
    check(BRACE1);
    check(single(), NOVALIDATE);
    check(BRACE2);
    Err.or(IMPLVAL);
  }

  /**
   * [ 66] Parses a Pragma.
   * @return true if pragma was found
   * @throws QueryException xquery exception
   */
  private boolean pragma() throws QueryException {
    if(!consumeWS2(PRAGMA)) return false;

    do {
      // ignore all pragmas...
      final QNm name = new QNm(qName(PRAGMAINCOMPLETE), ctx);
      if(!name.ns()) Err.or(NSMISS, name);
      char c = curr();
      if(c != '#' && !ws(c)) Err.or(PRAGMAINCOMPLETE);

      tok.reset();
      while(c != '#' || next() != ')') {
        if(c == 0) Err.or(PRAGMAINCOMPLETE);
        tok.add(consume());
        c = curr();
      }
      tok.finish();
      qp += 2;
    } while(consumeWS(PRAGMA));
    return true;
  }

  /**
   * [ 68] Parses a PathExpr.
   * [ 69] Parses a RelativePathExpr.
   * @return query expression
   * @throws QueryException xquery exception
   */
  private Expr path() throws QueryException {
    final int s = consume('/') ? consume('/') ? 2 : 1 : 0;

    final Expr ex = step();
    if(ex == null) {
      if(s > 1) Err.or(PATHMISS);
      return s == 0 ? null : new Root();
    }

    final boolean slash = consume('/');
    final boolean step = ex instanceof Step;
    if(!slash && s == 0 && !step) return ex;

    Expr[] list = {};
    if(s == 2) list = add(list, descOrSelf());
    
    final Expr root = s > 0 ? new Root() : !step ? ex : null;
    if(root != ex) list = add(list, ex);

    if(slash) {
      do {
        if(consume('/')) list = add(list, descOrSelf());
        final Expr st = check(step(), PATHMISS);
        if(!(st instanceof Context)) list = add(list, st);
      } while(consume('/'));
    }
    
    if(list.length == 0) return root;
    
    // check if all steps are axis steps
    boolean axes = true;
    final Step[] tmp = new Step[list.length];
    for(int l = 0; l < list.length; l++) {
      axes &= list[l] instanceof Step;
      if(axes) tmp[l] = (Step) list[l];
    }
    return axes ? AxisPath.get(root, tmp) : new MixedPath(root, list);
  }

  /**
   * Returns a standard descendant-or-self::node() step.
   * @return step
   */
  private Step descOrSelf() {
    return Step.get(Axis.DESCORSELF, Test.NODE);
  }

  /**
   * [ 70] Parses a StepExpr.
   * @return query expression
   * @throws QueryException xquery exception
   */
  private Expr step() throws QueryException {
    final Expr e = filter();
    return e != null ? e : axis();
  }

  /**
   * [ 71] Parses an AxisStep.
   * @return query expression
   * @throws QueryException xquery exception
   */
  private Step axis() throws QueryException {
    Axis ax = null;
    Test test = null;

    if(consumeWS2(DOT2)) {
      ax = Axis.PARENT;
      test = Test.NODE;
    } else if(consume('@')) {
      ax = Axis.ATTR;
      test = test(true);
      if(test == null) Err.or(NOATTNAME);
    } else {
      for(final Axis a : Axis.values()) {
        if(consumeWS(a.name, COL2, NOLOCSTEP)) {
          consumeWS2(COL2);
          alter = NOLOCSTEP;
          ap = qp;
          ax = a;
          test = test(a == Axis.ATTR);
          break;
        }
      }
    }
    if(ax == null) {
      ax = Axis.CHILD;
      test = test(false);
      if(test != null && test.type == Type.ATT) ax = Axis.ATTR;
    }
    if(test == null) return null;

    Expr[] pred = {};
    while(consumeWS2(BR1)) {
      pred = add(pred, expr());
      check(BR2);
    }
    return Step.get(ax, test, pred);
  }

  /**
   * [ 78] Parses a NodeTest.
   * [ 79] Parses a NameTest.
   * [123] Parses a KindTest.
   * @param att attribute flag
   * @return query expression
   * @throws QueryException xquery exception
   */
  private Test test(final boolean att) throws QueryException {
    final int p = qp;
    final char ch = curr();
    if(XMLToken.isXMLLetter(ch)) {
      final byte[] name = qName(null);
      if(consumeWS(PAR1)) {
        final Type type = Type.node(new QNm(name));
        if(type != null) {
          tok.reset();
          while(!consumeWS(PAR2)) {
            if(curr() == 0) Err.or(TESTINCOMPLETE);
            tok.add(consume());
          }
          final byte[] ext = tok.finish();
          final QNm qn = checkTest(type, ext);
          return type == Type.NOD ? Test.NODE : new KindTest(type, qn);
        }
      } else {
        // name test "pre:tag"
        if(contains(name, ':')) {
          skipWS();
          return new NameTest(new QNm(name, ctx), NameTest.Kind.STD, att);
        }
        // name test "tag"
        if(!consume(':')) {
          skipWS();
          return att ? new NameTest(new QNm(name), NameTest.Kind.NAME, att) :
            new NameTest(new QNm(name, Uri.uri(ctx.nsElem)),
                NameTest.Kind.STD, att);
        }
        // name test "pre:*"
        if(consume('*')) {
          final QNm nm = new QNm(EMPTY);
          nm.uri = Uri.uri(ctx.ns.uri(name));
          return new NameTest(nm, NameTest.Kind.NS, att);
        }
      }
    } else if(consume('*')) {
      // name test "*"
      if(!consume(':')) return new NameTest(att);
      // name test "*:tag"
      return new NameTest(new QNm(qName(null)), NameTest.Kind.NAME, att);
    }
    qp = p;
    return null;
  }

  /**
   * [ 81] Parses a FilterExpr.
   * [ 82] Parses a PredicateList.
   * [ 83] Parses a Predicate.
   * @return query expression
   * @throws QueryException xquery exception
   */
  private Expr filter() throws QueryException {
    final Expr e = primary();
    if(!consumeWS2(BR1)) return e;

    if(e == null) Err.or(PREDMISSING);
    Expr[] pred = {};
    do { pred = add(pred, expr()); check(BR2); } while(consumeWS2(BR1));
    return new Pred(e, pred);
  }

  /**
   * [ 84] Parses a PrimaryExpr.
   * [ 85] Parses a Literal.
   * [ 87] Parses a VarRef.
   * [ 90] Parses a ContextItem.
   * @return query expression
   * @throws QueryException xquery exception
   */
  private Expr primary() throws QueryException {
    skipWS();
    // numbers
    final char c = curr();
    if(digit(c)) return numericLiteral();
    // decimal/double values or context item
    if(c == '.' && next() != '.') {
      consume('.');
      return !digit(curr()) ? new Context() : decimalLiteral(
          new TokenBuilder().add('.'));
    }
    // strings
    if(quote(c)) return new Str(stringLiteral(), true);
    // variables
    if(c == '$') {
      final Var var = new Var(varName());
      if(ctx.vars.get(var) == null) Err.or(VARNOTDEFINED, var);
      return new VarCall(var);
    }
    // parentheses
    if(c == '(' && next() != '#') return parenthesized();
    // direct constructor
    if(c == '<') return constructor();
    // function calls and computed constructors
    if(letter(c)) {
      Expr e = functionCall();
      if(e != null) return e;
      e = computedConstructor();
      if(e != null) return e;
      // ordered expression
      if(consumeWS(ORDERED) || consumeWS(UNORDERED))
        return enclosed(NOENCLEXPR);
    }
    return null;
  }

  /**
   * [ 86] Parses a NumericLiteral.
   * [141] Parses an IntegerLiteral.
   * @return query expression
   * @throws QueryException xquery exception
   */
  private Expr numericLiteral() throws QueryException {
    tok.reset();
    while(digit(curr())) tok.add(consume());
    if(letter(curr())) return checkDbl(tok);
    if(!consume('.')) {
      final long l = toLong(tok.finish());
      if(l == Long.MIN_VALUE) Err.or(BOUNDS, tok);
      return Itr.get(l);
    }
    tok.add('.');
    return decimalLiteral(tok);
  }

  /**
   * Checks if a number is followed by a whitespace.
   * @param tb token builder
   * @return expression
   * @throws QueryException xquery exception
   */
  private Expr checkDbl(final TokenBuilder tb) throws QueryException {
    if(!consume('e') && !consume('E')) Err.or(NUMBERWS);
    return decimalDouble(tb);
  }

  /**
   * [ 88] Parses a VarName.
   * @return query expression
   * @throws QueryException xquery exception
   */
  private QNm varName() throws QueryException {
    check(DOLLAR);
    final QNm name = new QNm(qName(NOVARNAME));
    if(name.ns()) name.uri = Uri.uri(ctx.ns.uri(name.pre()));
    ctx.ns.uri(name);
    return name;
  }

  /**
   * [ 89] Parses a ParenthesizedExpr.
   * @return query expression
   * @throws QueryException xquery exception
   */
  private Expr parenthesized() throws QueryException {
    check(PAR1);
    final Expr e = expr();
    check(PAR2);
    return e == null ? Seq.EMPTY : e;
  }

  /**
   * [ 93] Parses a FunctionCall.
   * @return query expression
   * @throws QueryException xquery exception
   */
  private Expr functionCall() throws QueryException {
    final int p = qp;
    final QNm name = new QNm(qName(null));
    if(!consumeWS2(PAR1) || Type.node(name) != null) {
      qp = p;
      return null;
    }

    // name and opening bracket found
    Expr[] exprs = {};
    while(curr() != 0) {
      if(consumeWS2(PAR2)) {
        alter = new Object[] { name };
        ap = qp;
        ctx.ns.uri(name);
        name.uri = Uri.uri(name.ns() ? ctx.ns.uri(name.pre()) : ctx.nsFunc);
        final Expr[] args = exprs;
        final Expr func = ctx.fun.get(name, args);
        if(func != null) {
          alter = null;
          return func;
        }
        qp = p;
        return null;
      }
      if(exprs.length != 0) check(COMMA);
      exprs = add(exprs, single());
    }
    Err.or(FUNCMISS, name.str());
    return null;
  }

  /**
   * [ 94] Parses a Constructor.
   * [ 95] Parses a DirectConstructor.
   * @return query expression
   * @throws QueryException xquery exception
   */
  private Expr constructor() throws QueryException {
    check('<');
    return consume('!') ? dirCommentConstructor() :
      consume('?') ? dirPIConstructor() : dirElemConstructor();
  }

/**
   * [ 96] Parses a DirElemConstructor.
   * [ 97-100] Parses attributes.
   * @return query expression
   * @throws QueryException xquery exception
   */
  private Expr dirElemConstructor() throws QueryException {
    if(skipWS()) Err.or(NOTAGNAME);
    final QNm open = new QNm(qName(NOTAGNAME));
    consumeWSS();

    Expr[] cont = {};
    final Atts ns = new Atts();
    final int s = ctx.ns.size();

    // parse attributes...
    while(XMLToken.isXMLLetter(curr())) {
      final byte[] atn = qName(null);
      Expr[] attv = {};

      consumeWSS();
      check('=');
      consumeWSS();
      final char delim = consume();
      if(!quote(delim)) Err.or(NOQUOTE, found());
      final TokenBuilder tb = new TokenBuilder();

      boolean simple = true;
      do {
        while(!consume(delim)) {
          final char c = curr();
          if(c == '{') {
            if(next() == '{') {
              tb.add(consume());
              consume();
            } else {
              final byte[] text = tb.finish();
              if(text.length != 0) {
                attv = add(attv, Str.get(text));
              } else {
                attv = add(attv, enclosed(NOENCLEXPR));
                simple = false;
              }
              tb.reset();
            }
          } else if(c == '}') {
            qp++;
            check('}');
            tb.add('}');
          } else if(c == '<' || c == 0) {
            Err.or(NOQUOTE, found());
          } else if(c == 0x0A || c == 0x09) {
            qp++;
            tb.add(' ');
          } else {
            entity(tb);
          }
        }
        if(!consume(delim)) break;
        tb.add(delim);
      } while(true);

      if(tb.size != 0) attv = add(attv, Str.get(tb.finish()));

      if(eq(atn, XMLNS)) {
        if(!simple) Err.or(NSCONS);
        final byte[] v = attv.length == 0 ? EMPTY : ((Str) attv[0]).str();
        if(!open.ns()) open.uri = Uri.uri(v);
        addNS(ns, atn, v);
      } else if(startsWith(atn, XMLNS)) {
        if(!simple) Err.or(NSCONS);
        final byte[] v = attv.length == 0 ? EMPTY : ((Str) attv[0]).str();
        if(v.length == 0) Err.or(NSEMPTYURI);
        ctx.ns.add(new QNm(atn, Uri.uri(v)));
        addNS(ns, atn, v);
      } else {
        cont = add(cont, new CAttr(new QNm(atn), attv, false));
      }
      if(!consumeWSS()) break;
    }

    if(consume('/')) {
      check('>');
    } else {
      check('>');
      while(curr() != '<' || next() != '/') {
        final Expr e = dirElemContent(open);
        if(e == null) continue;
        cont = add(cont, e);
      }
      qp += 2;

      if(skipWS()) Err.or(NOTAGNAME);
      final byte[] close = qName(NOTAGNAME);
      consumeWSS();
      check('>');
      if(!eq(open.str(), close)) Err.or(TAGWRONG, open.str(), close);
    }

    ctx.ns.size(s);
    return new CElem(open, cont, ns);
  }

  /**
   * Checks the uniqueness of the namespace and adds it to the attributes.
   * @param ns namespace array
   * @param k namespace
   * @param v uri
   * @throws QueryException xquery exception
   */
  private void addNS(final Atts ns, final byte[] k, final byte[] v)
      throws QueryException {

    final int i = indexOf(k, ':');
    final byte[] key = i == -1 ? EMPTY : substring(k, i + 1);
    if(!ns.addUnique(key, v)) Err.or(DUPLNSDEF, k);
  }

  /**
   * [101] Parses a DirElemContent.
   * @param tag opening tag
   * @return query expression
   * @throws QueryException xquery exception
   */
  private Expr dirElemContent(final QNm tag) throws QueryException {
    final TokenBuilder tb = new TokenBuilder();
    do {
      final char c = curr();
      if(c == '<') {
        if(consumeWS2(CDATA)) {
          tb.add(cDataSection());
          tb.ent = true;
        } else {
          final byte[] txt = text(tb);
          if(txt != null) return Str.get(txt);
          return next() == '/' ? null : constructor();
        }
      } else if(c == '{') {
        if(next() == '{') {
          tb.add(consume());
          consume();
        } else {
          final byte[] txt = text(tb);
          return txt != null ? Str.get(txt) : enclosed(NOENCLEXPR);
        }
      } else if(c == '}') {
        consume();
        check('}');
        tb.add('}');
      } else if(c == 0) {
        Err.or(NOCLOSING, tag);
      } else {
        entity(tb);
      }
    } while(true);
  }

  /**
   * Returns a text array.
   * @param tb token builder
   * @return text or null reference
   */
  private byte[] text(final TokenBuilder tb) {
    final byte[] t = tb.finish();
    return t.length == 0 || !tb.ent && !ctx.spaces && ws(t) ? null : t;
  }

  /**
   * [103] Parses a DirCommentConstructor.
   * @return query expression
   * @throws QueryException xquery exception
   */
  private Expr dirCommentConstructor() throws QueryException {
    check('-');
    check('-');
    final TokenBuilder tb = new TokenBuilder();
    do {
      while(not('-')) tb.add(consume());
      consume();
      if(consume('-')) {
        check('>');
        return new CComm(Str.get(tb.finish()));
      }
      tb.add('-');
    } while(true);
  }

  /**
   * [105-106] Parses a DirPIConstructor.
   * @return query expression
   * @throws QueryException xquery exception
   */
  private Expr dirPIConstructor() throws QueryException {
    if(consumeWSS()) Err.or(PIXML, EMPTY);
    final byte[] str = qName(PIWRONG);
    final Expr pi = Str.get(str);
    if(str.length == 0 || eq(lc(str), XML)) Err.or(PIXML, pi);

    final boolean space = skipWS();
    final TokenBuilder tb = new TokenBuilder();
    do {
      while(not('?')) {
        if(!space) Err.or(PIWRONG);
        tb.add(consume());
      }
      consume();
      if(consume('>')) return new CPI(pi, Str.get(tb.finish()));
      tb.add('?');
    } while(true);
  }

  /**
   * [107] Parses a CDataSection.
   * @return CData
   * @throws QueryException xquery exception
   */
  private byte[] cDataSection() throws QueryException {
    final TokenBuilder tb = new TokenBuilder();
    while(true) {
      while(not(']')) tb.add(consume());
      consume();
      if(curr() == ']' && next() == '>') {
        qp += 2;
        return tb.finish();
      }
      tb.add(']');
    }
  }

  /**
   * [109] Parses a ComputedConstructor.
   * @return query expression
   * @throws QueryException xquery exception
   */
  private Expr computedConstructor() throws QueryException {
    final int p = qp;
    if(consumeWS(DOCUMENT)) return consume(compDocConstructor(), p);
    else if(consumeWS(ELEMENT)) return consume(compElemConstructor(), p);
    else if(consumeWS(ATTRIBUTE)) return consume(compAttrConstructor(), p);
    else if(consumeWS(TEXT)) return consume(compTextConstructor(), p);
    else if(consumeWS(COMMENT)) return consume(compCommentConstructor(), p);
    else if(consumeWS(PI)) return consume(compPIConstructor(), p);
    return null;
  }

  /**
   * [110] Parses a CompDocConstructor.
   * @return query expression
   * @throws QueryException xquery exception
   */
  private Expr compDocConstructor() throws QueryException {
    if(!consumeWS2(BRACE1)) return null;
    final Expr e = check(expr(), NODOCCONS);
    consumeWS2(BRACE2);
    return new CDoc(e);
  }

  /**
   * [111] Parses a CompElemConstructor.
   * [112] Parses a ContextExpr.
   * @return query expression
   * @throws QueryException xquery exception
   */
  private Expr compElemConstructor() throws QueryException {
    skipWS();

    Expr name;
    if(XMLToken.isXMLLetter(curr())) {
      name = Str.get(qName(null));
    } else {
      if(!consumeWS2(BRACE1)) return null;
      name = check(expr(), NOTAGNAME);
      check(BRACE2);
    }

    check(BRACE1);
    final Expr e = expr();
    check(BRACE2);
    return new CElem(name, e == null ? new Expr[0] : new Expr[] { e },
        new Atts());
  }

  /**
   * [113] Parses a CompAttrConstructor.
   * @return query expression
   * @throws QueryException xquery exception
   */
  private Expr compAttrConstructor() throws QueryException {
    skipWS();

    Expr nm;
    if(XMLToken.isXMLLetter(curr())) {
      nm = Str.get(qName(null));
    } else {
      if(!consumeWS2(BRACE1)) return null;
      nm = expr();
      check(BRACE2);
    }

    check(BRACE1);
    final Expr e = expr();
    check(BRACE2);
    return new CAttr(nm, new Expr[] { e == null ? Seq.EMPTY : e }, true);
  }

  /**
   * [114] Parses a CompTextConstructor.
   * @return query expression
   * @throws QueryException xquery exception
   */
  private Expr compTextConstructor() throws QueryException {
    if(!consumeWS2(BRACE1)) return null;
    final Expr e = check(expr(), NOTXTCONS);
    check(BRACE2);
    return new CText(e);
  }

  /**
   * [115] Parses a CompCommentConstructor.
   * @return query expression
   * @throws QueryException xquery exception
   */
  private Expr compCommentConstructor() throws QueryException {
    if(!consumeWS2(BRACE1)) return null;
    final Expr e = check(expr(), NOCOMCONS);
    consumeWS2(BRACE2);
    return new CComm(e);
  }

  /**
   * [116] Parses a CompPIConstructor.
   * @return query expression
   * @throws QueryException xquery exception
   */
  private Expr compPIConstructor() throws QueryException {
    skipWS();
    Expr name;
    if(XMLToken.isXMLLetter(curr())) {
      name = Str.get(ncName(null));
    } else {
      if(!consumeWS2(BRACE1)) return null;
      name = check(expr(), PIWRONG);
      check(BRACE2);
    }

    check(BRACE1);
    Expr e = expr();
    if(e == null) e = Seq.EMPTY;
    check(BRACE2);
    return new CPI(name, e);
  }

  /**
   * [117] Parses a SimpleType.
   * @return sequence type
   * @throws QueryException xquery exception
   */
  private SeqType simpleType() throws QueryException {
    final QNm type = new QNm(qName(TYPEINVALID));
    ctx.ns.uri(type);
    skipWS();
    final SeqType seq = new SeqType(type, consume('?') ? 1 : 0, false);

    if(seq.type == null) {
      final byte[] uri = type.uri.str();
      if(uri.length == 0 && type.ns()) Err.or(PREUNKNOWN, type.pre());
      final byte[] ln = type.ln();
      Err.or(eq(uri, Type.NOT.uri) && (eq(Type.NOT.name, ln) ||
        eq(Type.AAT.name, ln))  ? CASTUNKNOWN : TYPEUNKNOWN, type);
    }
    return seq;
  }

  /**
   * [119] Parses a SequenceType.
   * [120] Parses an OccurrenceIndicator.
   * [123] Parses a KindTest.
   * @return sequence type
   * @throws QueryException xquery exception
   */
  private SeqType sequenceType() throws QueryException {
    final QNm type = new QNm(qName(TYPEINVALID));
    tok.reset();
    final boolean par = consumeWS(PAR1);
    if(par) {
      while(!consumeWS(PAR2)) {
        if(curr() == 0) Err.or(FUNCMISS, type.str());
        tok.add(consume());
      }
    }
    skipWS();
    final int mode = consume('?') ? 1 : consume('+') ? 2 :
      consume('*') ? 3 : 0;
    if(type.ns()) type.uri = Uri.uri(ctx.ns.uri(type.pre()));

    final byte[] ext = tok.finish();
    final SeqType seq = new SeqType(type, mode, true);
    if(seq.type == null) Err.or(par ? NOTYPE : TYPEUNKNOWN, type, par);
    if(seq.type == Type.EMP && mode != 0) Err.or(EMPTYSEQOCC, seq.type);
    seq.ext = checkTest(seq.type, ext);
    skipWS();
    return seq;
  }

  /**
   * Checks the arguments of the kind test.
   * @param t type
   * @param k kind arguments
   * @return arguments
   * @throws QueryException query exception
   */
  private QNm checkTest(final Type t, final byte[] k) throws QueryException {
    if(k.length == 0) return null;
    if(!t.node() || t == Type.COM || t == Type.TXT || t == Type.DOC)
      Err.or(TESTINVALID, t, k);

    byte[] nm = delete(delete(k, '\''), '"');
    final int i = indexOf(nm, ',');
    if(i != -1) {
      final QNm test = new QNm(trim(substring(nm, i + 1)), ctx);
      if(!eq(test.uri.str(), XSURI)) Err.or(TESTINVALID, t, test);
      nm = trim(substring(nm, 0, i));
    }

    return (t == Type.ELM || t == Type.ATT) && eq(nm, WILD) ? null :
      new QNm(nm, ctx);
  }

  /**
   * [142] Parses a DecimalLiteral.
   * @param tb start of number
   * @return query expression
   * @throws QueryException xquery exception
   */
  private Expr decimalLiteral(final TokenBuilder tb) throws QueryException {
    if(letter(curr())) return checkDbl(tb);
    while(digit(curr())) tb.add(consume());
    return letter(curr()) ? checkDbl(tb) : new Dec(tb.finish());
  }

  /**
   * [142] Parses a DecimalDouble.
   * @param tb start of number
   * @return query expression
   * @throws QueryException xquery exception
   */
  private Expr decimalDouble(final TokenBuilder tb) throws QueryException {
    tb.add('e');
    if(curr() == '+' || curr() == '-') tb.add(consume());
    boolean dig = false;
    while(digit(curr())) {
      tb.add(consume());
      dig = true;
    }
    if(!dig) Err.or(NUMBERINC);
    if(letter(curr())) Err.or(NUMBERWS);
    return Dbl.get(tb.finish());
  }

  /**
   * [144] Parses a StringLiteral.
   * @return query expression
   * @throws QueryException xquery exception
   */
  private byte[] stringLiteral() throws QueryException {
    skipWS();
    final char delim = curr();
    if(!quote(delim)) Err.or(NOQUOTE, found());
    consume();
    tok.reset();
    while(true) {
      while(!consume(delim)) {
        if(curr() == 0) Err.or(NOQUOTE, found());
        entity(tok);
      }
      if(!consume(delim)) break;
      tok.add(delim);
    }
    return tok.finish();
  }

  /**
   * [11-169] Parses a TryClause.
   * @return query expression
   * @throws QueryException xquery exception
   */
  private Expr tryCatch() throws QueryException {
    if(!consumeWS2(TRY)) return null;

    final Expr tr = enclosed(NOENCLEXPR);
    check(CATCH);
    
    Catch[] ct = {};
    do {
      QNm[] codes = {};
      do {
        consumeWS();
        if(XMLToken.isXMLLetter(curr())) {
          codes = Array.add(codes, new QNm(qName(QNAMEINV)));
        } else {
          check("*");
          codes = Array.add(codes, null);
        }
      } while(consume(PIPE));
      
      Var var1 = null;
      Var var2 = null;
      Var var3 = null;
      final int s = ctx.vars.size();
      if(consumeWS2(PAR1)) {
        var1 = new Var(varName());
        ctx.vars.add(var1);
        if(consumeWS2(COMMA)) {
          var2 = new Var(varName());
          if(var1.name.eq(var2.name)) Err.or(VARDEFINED, var2);
          ctx.vars.add(var2);
          if(consumeWS2(COMMA)) {
            var3 = new Var(varName());
            if(var1.name.eq(var3.name) || var2.name.eq(var3.name))
              Err.or(VARDEFINED, var3);
            ctx.vars.add(var3);
          }
        }
        check(PAR2);
      }
      final Expr ex = enclosed(NOENCLEXPR);
      ctx.vars.reset(s);
      ct = Array.add(ct, new Catch(ex, codes, var1, var2, var3));
    } while(consume(CATCH));
    
    return new Try(tr, ct);
  }

  /**
   * [FT144] Parses an FTSelection.
   * [FT157] Parses an FTPosFilter.
   * [FT158] Parses an FTOrder.
   * [FT159] Parses an FTWindow. 
   * [FT160] Parses an FTDistance. 
   * [FT162] Parses an FTScope.
   * [FT164] Parses an FTContent.
   * @return query expression
   * @throws QueryException xquery exception
   */
  private FTExpr ftSelection() throws QueryException {
    final FTPos sel = new FTPos(ftOr());

    while(true) {
      if(consumeWS(ORDERED)) {
        sel.ordered = true;
      } else if(consumeWS(WINDOW)) {
        sel.window = additive();
        sel.wunit = ftUnit();
      } else if(consumeWS(DISTANCE)) {
        sel.dist = ftRange();
        if(sel.dist == null) Err.or(FTRANGE);
        sel.dunit = ftUnit();
      } else if(consumeWS(AT)) {
        sel.start = consumeWS(START);
        if(!sel.start) sel.end = consumeWS(END);
        if(!sel.start && !sel.end) Err.or(INCOMPLETE);
      } else if(consumeWS(ENTIRE)) {
        check(CONTENT);
        sel.content = true;
      } else {
        final boolean same = consumeWS(SAME);
        final boolean diff = !same && consumeWS(DIFFERENT);
        if(!same && !diff) break;
        sel.same = same;
        sel.different = diff;
        if(consumeWS(SENTENCE)) sel.sdunit = FTUnit.SEN;
        else if(consumeWS(PARAGRAPH)) sel.sdunit = FTUnit.PAR;
        else Err.or(INCOMPLETE);
      }
    }

    sel.weight = consumeWS(WEIGHT) ? range() : Dbl.ONE;
    return sel;
  }

  /**
   * [FT145] Parses FTOr.
   * @return query expression
   * @throws QueryException xquery exception
   */
  private FTExpr ftOr() throws QueryException {
    final FTExpr e = ftAnd();
    if(!consumeWS(FTOR)) return e;

    FTExpr[] list = { e };
    do list = Array.add(list, ftAnd()); while(consumeWS(FTOR));
    return new FTOr(list);
  }

  /**
   * [FT146] Parses FTAnd.
   * @return query expression
   * @throws QueryException xquery exception
   */
  private FTExpr ftAnd() throws QueryException {
    final FTExpr e = ftMildNot();
    if(!consumeWS(FTAND)) return e;

    FTExpr[] list = { e };
    do list = Array.add(list, ftMildNot()); while(consumeWS(FTAND));
    return new FTAnd(list);
  }

  /**
   * [FT147] Parses FTMildNot.
   * @return query expression
   * @throws QueryException xquery exception
   */
  private FTExpr ftMildNot() throws QueryException {
    final FTExpr e = ftUnaryNot();
    if(!consumeWS(NOT)) return e;

    FTExpr[] list = { e };
    do {
      check(IN); list = Array.add(list, ftUnaryNot());
    } while(consumeWS(NOT));
    return new FTMildNot(list);
  }

  /**
   * [FT148] Parses FTUnaryNot.
   * @return query expression
   * @throws QueryException xquery exception
   */
  private FTExpr ftUnaryNot() throws QueryException {
    final boolean not = consumeWS(FTNOT);
    final FTOptions e = ftPrimaryWithOptions();
    return not ? new FTNot(e) : e;
  }

  /**
   * [FT149] Parses FTPrimaryWithOptions.
   * @return query expression
   * @throws QueryException xquery exception
   */
  private FTOptions ftPrimaryWithOptions() throws QueryException {
    final FTOpt opt = new FTOpt();
    final FTOptions ftopt = new FTOptions(ftPrimary(), opt);
    while(ftMatchOption(opt));
    return ftopt;
  }

  /**
   * [FT150] Parses FTPrimary.
   * [FT151] Parses FTWords.
   * [FT152] Parses FTWordsValue.
   * [FT154] Parses FTAnyallOption.
   * [FT155] Parses FTTimes.
   * @return query expression
   * @throws QueryException xquery exception
   */
  private FTExpr ftPrimary() throws QueryException {
    if(pragma()) {
      check(BRACE1);
      final FTExpr e = ftSelection();
      check(BRACE2);
      return e;
    }

    if(consumeWS(PAR1)) {
      final FTExpr e = ftSelection();
      check(PAR2);
      return e;
    }

    skipWS();
    final Expr e = quote(curr()) ? Str.get(stringLiteral()) :
      enclosed(NOENCLEXPR);

    // FTAnyAllOption
    FTMode mode = FTMode.ANY;
    if(consumeWS(ALL)) {
      mode = consumeWS(WORDS) ? FTMode.ALLWORDS : FTMode.ALL;
    } else if(consumeWS(ANY)) {
      mode = consumeWS(WORD) ? FTMode.ANYWORD : FTMode.ANY;
    } else if(consumeWS(PHRASE)) {
      mode = FTMode.PHRASE;
    }

    // FTTimes
    Expr[] occ = null;
    if(consumeWS(OCCURS)) {
      occ = ftRange();
      if(occ == null) Err.or(FTRANGE);
      consumeWS(TIMES);
    }
    return new FTWords(e, mode, occ);
  }

  /**
   * [FT156] Parses an FTRange.
   * @return query expression
   * @throws QueryException xquery exception
   */
  private Expr[] ftRange() throws QueryException {
    final Expr[] occ = { Itr.get(1), Itr.get(Long.MAX_VALUE) };
    if(consumeWS(EXACTLY)) {
      occ[0] = additive();
      occ[1] = occ[0];
    } else if(consumeWS(AT)) {
      if(consumeWS(LEAST)) {
        occ[0] = additive();
      } else {
        check(MOST);
        occ[0] = Itr.get(0);
        occ[1] = additive();
      }
    } else if(consumeWS(FROM)) {
      occ[0] = additive();
      check(TO);
      occ[1] = additive();
    } else {
      return null;
    }
    return occ;
  }

  /**
   * [FT156] Parses an FTUnit.
   * @return query expression
   * @throws QueryException xquery exception
   */
  private FTUnit ftUnit() throws QueryException {
    if(consumeWS(WORDS)) return FTUnit.WRD;
    if(consumeWS(SENTENCES)) return FTUnit.SEN;
    if(consumeWS(PARAGRAPHS)) return FTUnit.PAR;
    Err.or(INCOMPLETE);
    return null;
  }

  /**
   * [FT154] Parses an FTMatchOption.
   * @param opt options instance
   * @return false if no options were found
   * @throws QueryException xquery exception
   */
  private boolean ftMatchOption(final FTOpt opt) throws QueryException {
    // [CG] XQuery/FTMatchOptions: thesaurus

    if(consumeWS(LOWERCASE)) {
      if(opt.is(FTOpt.LC) || opt.is(FTOpt.UC) || opt.is(FTOpt.CS))
        Err.or(FTCASE);
      opt.set(FTOpt.CS, true);
      opt.set(FTOpt.LC, true);
    } else if(consumeWS(UPPERCASE)) {
      if(opt.is(FTOpt.LC) || opt.is(FTOpt.UC) || opt.is(FTOpt.CS))
        Err.or(FTCASE);
      opt.set(FTOpt.CS, true);
      opt.set(FTOpt.UC, true);
    } else if(consumeWS(CASE)) {
      if(opt.is(FTOpt.LC) || opt.is(FTOpt.UC) || opt.is(FTOpt.CS))
        Err.or(FTCASE);
      opt.set(FTOpt.CS, consumeWS(SENSITIVE));
      if(!opt.is(FTOpt.CS)) check(INSENSITIVE);
    } else if(consumeWS(DIACRITICS)) {
      if(opt.is(FTOpt.DC)) Err.or(FTDIA);
      opt.set(FTOpt.DC, consumeWS(SENSITIVE));
      if(!opt.is(FTOpt.DC)) check(INSENSITIVE);
    } else if(consumeWS(LANGUAGE)) {
      opt.ln = lc(stringLiteral());
      if(!eq(opt.ln, EN)) Err.or(FTLAN, opt.ln);
    } else if(consumeWS(OPTION)) {
      optionDecl();
    } else {
      final int p = qp;
      final boolean with = consumeWS(WITH);
      if(!with && !consumeWS(WITHOUT)) return false;

      if(consumeWS2(STEMMING)) {
        opt.set(FTOpt.ST, with);
      } else if(consumeWS2(THESAURUS)) {
        opt.set(FTOpt.TS, with);
        if(with) {
          final boolean par = consumeWS2(PAR1);
          if(consumeWS2(AT)) {
            ftThesaurusID();
          } else {
            check(DEFAULT);
          }
          while(par && consumeWS2(COMMA)) ftThesaurusID();
          if(par) check(PAR2);
          Err.or(FTTHES);
        }
      } else if(consumeWS(STOP)) {
        // add union/except
        check(WORDS);
        opt.sw = new Set();
        boolean union = false;
        boolean except = false;
        while(with) {
          if(consumeWS2(PAR1)) {
            do {
              final byte[] sl = stringLiteral();
              if(except) opt.sw.delete(sl);
              else if(!union || opt.sw.id(sl) == 0) opt.sw.add(sl);
            } while(consumeWS2(COMMA));
            check(PAR2);
          } else if(consumeWS2(AT)) {
            String fn = string(stringLiteral());
            if(ctx.stop != null) fn = ctx.stop.get(fn);
            
            IO fl = IO.get(fn);
            if(!fl.exists() && ctx.file != null) {
              fl = file.merge(fl);
              if(!fl.exists()) Err.or(NOSTOPFILE, fl);
            }
            
            try {
              for(final byte[] sl : split(norm(fl.content()), ' ')) {
                if(except) opt.sw.delete(sl);
                else if(!union || opt.sw.id(sl) == 0) opt.sw.add(sl);
              }
            } catch(final IOException ex) {
              Err.or(NOSTOPFILE, fl);
            }
          } else if(!union && !except) {
            Err.or(FTSTOP);
          }
          union = consumeWS2(UNION);
          except = !union && consumeWS2(EXCEPT);
          if(!union && !except) break;
        }
      } else if(consumeWS(DEFAULT)) {
        check(STOP);
        check(WORDS);
      } else if(consumeWS2(WILDCARDS)) {
        if(opt.is(FTOpt.FZ)) Err.or(FTFZWC);
        opt.set(FTOpt.WC, with);
      } else if(consumeWS2(FUZZY)) {
        if(opt.is(FTOpt.WC)) Err.or(FTFZWC);
        opt.set(FTOpt.FZ, with);
      } else {
        qp = p;
        return false;
      }
    }
    return true;
  }

  /**
   * [FT171] Parses an FTThesaurusID.
   * @throws QueryException xquery exception
   */
  private void ftThesaurusID() throws QueryException {
    stringLiteral();
    if(consumeWS2(RELATIONSHIP)) stringLiteral();
    if(ftRange() != null) check(LEVELS);
  }

  /**
   * [UP140] Parses a DeleteExpression.
   * @return query expression
   * @throws QueryException xquery exception
   */
  private Expr deletee() throws QueryException {
    final int p = qp;
    if(!consumeWS(DELETE)) return null;
    if(!consumeWS(NODES) && !consumeWS(NODE)) {
      qp = p;
      return null;
    }
    final Expr expr = single();
    Err.or(UPIMPL);
    return expr;
  }
  
  /**
   * Parses an NCName.
   * @param err optional error message
   * @return string
   * @throws QueryException xquery exception
   */
  private byte[] ncName(final Object[] err) throws QueryException {
    skipWS();
    tok.reset();
    if(ncName(true)) return tok.finish();
    if(err != null) Err.or(err);
    return EMPTY;
  }

  /**
   * Parses a QName.
   * @param err optional error message
   * @return string
   * @throws QueryException xquery exception
   */
  private byte[] qName(final Object[] err) throws QueryException {
    skipWS();
    tok.reset();
    final boolean ok = ncName(true);
    if(ok && consume(':')) ncName(false);
    if(!ok && err != null) Err.or(err);
    return tok.finish();
  }

  /**
   * Helper method for parsing NCNames.
   * @param first flag for first call
   * @return true for success
   */
  private boolean ncName(final boolean first) {
    char c = curr();
    if(!XMLToken.isXMLLetter(c)) {
      if(!first) qp--;
      return false;
    }
    if(!first) tok.add(':');
    do {
      tok.add(consume());
      c = curr();
      if(!XMLToken.isXMLLetterOrDigit(c) && c != '-' && c != '_' && c != '.')
        break;
    } while(c != 0);
    return true;
  }

  /**
   * Parse and convert entities.
   * @param tb token builder
   * @throws QueryException xquery exception
   */
  void entity(final TokenBuilder tb) throws QueryException {
    final String ent = ent(tb);
    if(ent != null)  Err.or(ENTINVALID, ent);
  }

  /**
   * Throws an exception if the specified expression is empty.
   * @param expr expression
   * @param err error message
   * @return expression
   * @throws QueryException xquery exception
   */
  private Expr check(final Expr expr, final Object[] err)
      throws QueryException {
    if(expr == null) Err.or(err);
    return expr;
  }

  /**
   * Checks for the specified character and throws an exception if
   * something else is found.
   * @param ch character to be found.
   * @throws QueryException xquery exception
   */
  private void check(final int ch) throws QueryException {
    if(!consume(ch)) Err.or(list(Character.toString((char) ch)),
        WRONGCHAR, (char) ch, found());
  }

  /**
   * Checks for the specified string and throws an exception if
   * something else is found.
   * @param s string to be found.
   * @throws QueryException xquery exception
   */
  private void check(final String s) throws QueryException {
    if(!consumeWS2(s)) Err.or(list(s), WRONGCHAR, s, found());
  }

  /**
   * Checks if the specified character is not found; if the input is
   * exhausted, throws an exception.
   * @param ch character to be found.
   * @return result of check
   * @throws QueryException xquery exception
   */
  private boolean not(final int ch) throws QueryException {
    final char c = curr();
    if(c == 0) Err.or(WRONGEND, ch);
    return c != ch;
  }

  /**
   * Consumes the specified expression or resets the query position.
   * @param expr expression
   * @param p query position
   * @return expression or null
   */
  private Expr consume(final Expr expr, final int p) {
    if(expr != null) return expr;
    qp = p;
    return expr;
  }

  /**
   * Consumes the specified token and surrounding whitespaces.
   * @param t token to consume
   * @return true if token was found
   * @throws QueryException xquery exception
   */
  private boolean consumeWS(final String t) throws QueryException {
    final int p = qp;
    final boolean ok = consumeWS2(t);
    final int q = qp;
    final int c = curr();
    final boolean ok2 = skipWS() || !XMLToken.isFirstLetter(t.charAt(0)) ||
        !XMLToken.isXMLLetterOrDigit(c) && c != '-';
    qp = ok2 ? q : p;
    return ok && ok2;
  }

  /**
   * Consumes the specified two strings or jumps back to the old query position.
   * @param s1 string to be consumed
   * @param s2 second string
   * @param expr alternative error message
   * @return result of check
   * @throws QueryException xquery exception
   */
  private boolean consumeWS(final String s1, final String s2,
      final Object[] expr) throws QueryException {
    final int p = qp;
    if(!consumeWS(s1)) return false;
    alter = expr;
    ap = qp;
    final int p2 = qp;
    final boolean ok = consumeWS2(s2);
    qp = ok ? p2 : p;
    return ok;
  }

  /**
   * Peeks forward and consumes the string if it equals the specified one.
   * @param str string to consume
   * @return true if string was found
   * @throws QueryException xquery exception
   */
  private boolean consumeWS2(final String str) throws QueryException {
    skipWS();
    return consume(str);
  }

  /**
   * Consumes all whitespace characters from the remaining query.
   * @return true if whitespaces were found
   * @throws QueryException xquery exception
   */
  private boolean skipWS() throws QueryException {
    final int p = qp;
    while(more()) {
      final int c = curr();
      if(c == '(' && next() == ':') {
        comment();
      } else {
        if(c <= 0 || c > ' ') return p != qp;
        qp++;
      }
    }
    return true;
  }

  /**
   * Consumes a comment.
   * @throws QueryException xquery exception
   */
  private void comment() throws QueryException {
    qp++;
    while(++qp < ql) {
      if(curr('(') && next() == ':') comment();
      if(curr(':') && next() == ')') {
        qp += 2;
        return;
      }
    }
    Err.or(COMCLOSE);
  }

  /**
   * Consumes all following whitespace characters.
   * @return true if whitespaces were found
   */
  private boolean consumeWSS() {
    final int p = qp;
    while(more()) {
      final int c = curr();
      if(c <= 0 || c > ' ') return p != qp;
      qp++;
    }
    return true;
  }

  /**
   * Throws the alternative error message.
   * @throws QueryException xquery exception
   */
  void error() throws QueryException {
    qp = ap;
    if(alter.length != 1) Err.or(alter);
    ctx.fun.funError((QNm) alter[0]);
    Err.or(FUNCUNKNOWN, ((QNm) alter[0]).str());
  }
  
  /**
   * Performs optional step checks.
   * @param axis axis
   * @param test test
   */
  @SuppressWarnings("unused")
  void checkStep(final Axis axis, final Test test) { }

  /**
   * Adds an expression to the specified array.
   * @param ar input array
   * @param e new expression
   * @return new array
   * @throws QueryException xquery exception
   */
  private static Expr[] add(final Expr[] ar, final Expr e)
      throws QueryException {
    if(e == null) Err.or(INCOMPLETE);
    final int size = ar.length;
    final Expr[] t = new Expr[size + 1];
    System.arraycopy(ar, 0, t, 0, size);
    t[size] = e;
    return t;
  }
}
