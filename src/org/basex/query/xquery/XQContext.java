package org.basex.query.xquery;

import static org.basex.Text.*;
import static org.basex.query.xquery.XQText.*;
import static org.basex.query.xquery.XQTokens.*;
import static org.basex.util.Token.*;
import org.basex.core.Prop;
import org.basex.core.proc.Check;
import org.basex.data.Data;
import org.basex.data.Nodes;
import org.basex.data.Serializer;
import org.basex.io.IO;
import org.basex.query.FTOpt;
import org.basex.query.FTPos;
import org.basex.query.QueryContext;
import org.basex.query.xquery.expr.Expr;
import org.basex.query.xquery.item.Bln;
import org.basex.query.xquery.item.DNode;
import org.basex.query.xquery.item.Dat;
import org.basex.query.xquery.item.Dtm;
import org.basex.query.xquery.item.Item;
import org.basex.query.xquery.item.Node;
import org.basex.query.xquery.item.QNm;
import org.basex.query.xquery.item.Seq;
import org.basex.query.xquery.item.Tim;
import org.basex.query.xquery.item.Type;
import org.basex.query.xquery.item.Uri;
import org.basex.query.xquery.iter.Iter;
import org.basex.query.xquery.iter.NodIter;
import org.basex.query.xquery.iter.NodeIter;
import org.basex.query.xquery.util.Err;
import org.basex.query.xquery.util.Functions;
import org.basex.query.xquery.util.Namespaces;
import org.basex.query.xquery.util.SeqBuilder;
import org.basex.query.xquery.util.Variables;
import org.basex.util.Array;
import org.basex.util.FTTokenizer;
import org.basex.util.StringList;

/**
 * XQuery Context.
 *
 * @author Workgroup DBIS, University of Konstanz 2005-08, ISC License
 * @author Christian Gruen
 */
public final class XQContext extends QueryContext {
  /** Namespaces. */
  public Namespaces ns = new Namespaces();
  /** Functions. */
  public Functions fun = new Functions();
  /** Variables. */
  public Variables vars = new Variables();

  /** Current context. */
  public Item item;
  /** Current context position. */
  public int pos;
  /** Current context size. */
  public int size;

  /** Current fulltext item. */
  public FTTokenizer ftitem;
  /** Current fulltext options. */
  public FTOpt ftopt;
  /** Current fulltext position filter. */
  public FTPos ftpos;

  /** Current Date. */
  public Dat date;
  /** Current DateTime. */
  public Dtm dtm;
  /** Current Time. */
  public Tim time;

  /** Default function namespace. */
  public Uri nsFunc = Uri.FN;
  /** Static Base URI. */
  public Uri baseURI = Uri.EMPTY;
  /** Default element namespace. */
  public Uri nsElem = Uri.EMPTY;
  /** Default collation. */
  public Uri collation = Uri.uri(URLCOLL);

  /** List of modules. */
  StringList modules = new StringList();
  /** List of loaded modules. */
  StringList modLoaded = new StringList();
  /** Used documents. */
  DNode[] docs = new DNode[0];
  /** Collections. */
  NodIter[] collect = new NodIter[0];
  /** Collection names. */
  byte[][] collName = new byte[0][];

  /** Default fulltext options. */
  private final FTOpt ftoptions = new FTOpt();
  /** Default boundary-space. */
  public Bln spaces = Bln.FALSE;
  /** Empty Order mode. */
  public Bln orderGreatest = Bln.FALSE;

  /** Default encoding (currently ignored). */
  byte[] encoding = token(Prop.ENCODING);
  /** Preserve Namespaces (currently ignored). */
  Bln nsPreserve = Bln.FALSE;
  /** Inherit Namespaces (currently ignored). */
  Bln nsInherit = Bln.FALSE;
  /** Ordering mode (currently ignored). */
  Bln ordered = Bln.FALSE;
  /** Construction mode (currently ignored). */
  Bln construct = Bln.FALSE;

  /** Reference to the expression root. */
  Expr root;

  @Override
  public XQContext compile(final Nodes nodes) throws XQException {
    inf = Prop.allInfo;
    info.reset();
    firstOpt = true;
    firstEval = true;

    // adds an existing document to the database array
    if(nodes != null) {
      docs = new DNode[nodes.size];
      for(int d = 0; d < docs.length; d++) {
        docs[d] = addNS(new DNode(nodes.data, nodes.pre[d], null, Type.DOC));
      }
      item = Seq.get(docs, docs.length);
      final NodIter col = new NodIter();
      for(int d = 0; d < docs.length; d++) col.add(docs[d]);
      collect = Array.add(collect, col);
      collName = Array.add(collName, docs[0].base());
    }

    // evaluates the query and returns the result
    if(inf) compInfo(QUERYCOMP);
    fun.comp(this);
    vars.comp(this);
    ftopt = ftoptions;
    root = root.comp(this);
    if(inf) compInfo(QUERYRESULT + "%", root);

    evalTime = System.nanoTime();
    return this;
  }

  @Override
  public XQResult eval(final Nodes nodes) throws XQException {
    // evaluates the query and returns the result
    return new XQResult(this, new SeqBuilder(iter()));
  }

  /**
   * Returns a result iterator.
   * @return result iterator
   * @throws XQException query exception
   */
  public Iter iter() throws XQException {
    // evaluates the query and returns the result
    try {
      return iter(root);
    } catch(final StackOverflowError e) {
      if(Prop.debug) e.printStackTrace();
      Err.or(XPSTACK);
      return null;
    }
  }

  @Override
  public void plan(final Serializer ser) throws Exception {
    vars.plan(ser);
    fun.plan(ser);
    root.plan(ser);
  }

  /** Maximum number of evaluation dumps. */
  private static final int MAXDUMP = 16;
  /** Query info counter. */
  private int cc;
  /** Current evaluation time. */
  private long evalTime;
  /** Info flag. */
  private boolean inf;

  /**
   * Evaluates the specified expression and returns an iterator.
   * @param expr expression to be evaluated
   * @return iterator
   * @throws XQException evaluation exception
   */
  public Iter iter(final Expr expr) throws XQException {
    checkStop();

    // skip query info for items
    final Iter iter = expr.iter(this);
    
    if(inf && !expr.i()) {
      final double t = ((System.nanoTime() - evalTime) / 10000) / 100.0;
      evalInfo(t + MS + ": " + expr.getClass().getSimpleName() + ": " + expr);
      inf = ++cc < MAXDUMP;
      if(!inf) evalInfo(EVALSKIP);
    }
    return iter;
  }

  /**
   * Returns the specified expression as an item. Empty sequences are
   * handled by the empty flag.
   * @param expr expression to be evaluated
   * @param call calling expression
   * @param empty if set to true, empty sequences are returned as null.
   * Otherwise, an error is thrown
   * @return iterator
   * @throws XQException evaluation exception
   */
  public Item atomic(final Expr expr, final Expr call, final boolean empty)
      throws XQException {

    if(expr.e()) {
      if(!empty) Err.empty(call);
      return null;
    }
    return expr.i() ? (Item) expr : iter(expr).atomic(call, empty);
  }

  /**
   * Adds a database instance or returns an existing one.
   * @param db database name or file path
   * @return database instance
   * @throws XQException evaluation exception
   */
  public DNode doc(final byte[] db) throws XQException {
    if(contains(db, '<') || contains(db, '>')) Err.or(INVDOC, db);

    // check if the collections contain the document
    if(collect.length > 0) {
      for(final NodIter ni : collect) {
        for(int n = 0; n < ni.size; n++) {
          if(eq(db, ni.list[n].base())) return (DNode) ni.list[n];
        }
      }
    }
    
    // check if the database has already been read
    final String dbname = string(db);
    for(final DNode d : docs) if(d.data.meta.dbname.equals(dbname)) return d;

    // check if the database has already been read
    final IO bxw = new IO(string(db));
    for(final DNode d : docs) if(d.data.meta.file.eq(bxw)) return d;

    // get database instance
    Data data = Check.check(dbname);
    if(data == null && file != null) data = Check.check(file.merge(bxw).path());
    if(data == null) Err.or(NODOC, bxw);

    // add document to array
    final int dl = docs.length;
    docs = Array.add(docs, new DNode(data, 0, null, Type.DOC));
    return addNS(docs[dl]);
  }

  /** Finishes the query execution.
  public void finish() {
    try { for(final DNode doc : docs) doc.data.close(); }
    catch(final IOException ex) { BaseX.debug(ex); }
  }*/
  
  /**
   * Adds namespaces from the specified document.
   * @param doc document
   * @return document
   * @throws XQException evaluation exception
   */
  private DNode addNS(final DNode doc) throws XQException {
    // add root namespaces
    NodeIter it = doc.child();
    Node node = null;
    while((node = it.next()) != null) {
      if(node.type != Type.ELM) continue;
      it = node.attr();
      while((node = it.next()) != null) {
        final QNm name = node.qname();
        if(eq(name.pre(), XMLNS)) ns.index(name);
      }
      break;
    }
    return doc;
  }

  /**
   * Adds a collection.
   * @param coll collection to be added
   * @throws XQException evaluation exception
   */
  public void addColl(final Node coll) throws XQException {
    // [CG] XQuery/add collection; check validity of specified collection
    final NodIter col = new NodIter();
    NodeIter ni = coll.child();
    ni = ni.next().child();
    Node nod;
    while((nod = ni.next()) != null) {
      if(nod.type != Type.ELM) continue;
      final NodeIter n = nod.attr();
      col.add(doc(n.next().str()));
    }
    collect = Array.add(collect, col);
    collName = Array.add(collName, coll.base());
  }

  /**
   * Returns the specified collection.
   * @param coll name of the collection to be returned.
   * @return collection
   * @throws XQException evaluation exception
   */
  public NodIter coll(final byte[] coll) throws XQException {
    if(coll == null) {
      if(collName.length == 0) Err.or(COLLDEF);
      return new NodIter(collect[0].list, collect[0].size);
    }
    
    if(contains(coll, '<') || contains(coll, '\\'))
      Err.or(COLLINV, cut(coll, 20));

    int c = -1, cl = collName.length;
    while(c < cl) {
      if(++c == cl) addColl(doc(coll));
      else if(!eq(collName[c], coll)) continue;
    }
    return new NodIter(collect[c].list, collect[c].size);
  }
  
  @Override
  public String toString() {
    return "Context[" + file + "]";
  }
}
