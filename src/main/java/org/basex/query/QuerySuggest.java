package org.basex.query;

import static org.basex.util.Token.*;

import java.util.*;

import org.basex.data.*;
import org.basex.index.path.*;
import org.basex.query.path.*;
import org.basex.query.util.*;
import org.basex.util.*;
import org.basex.util.list.*;

/**
 * This class analyzes the current path and gives suggestions for code
 * completions.
 *
 * @author BaseX Team 2005-12, BSD License
 * @author Christian Gruen
 */
public final class QuerySuggest extends QueryParser {
  /** Data reference. */
  private final Data data;
  /** All current path nodes. */
  private Stack<ArrayList<PathNode>> stack;
  /** All current path nodes. */
  private ArrayList<PathNode> all;
  /** Current path nodes. */
  private ArrayList<PathNode> curr;
  /** Hide flag. */
  private boolean show;
  /** Last tag name. */
  private byte[] tag;

  /**
   * Constructor.
   * @param q query
   * @param c query context
   * @param d data reference
   * @throws QueryException query exception
   */
  public QuerySuggest(final String q, final QueryContext c, final Data d)
      throws QueryException {
    super(q, null, c);
    data = d;
    checkInit();
  }

  /**
   * Sorts and returns the query suggestions.
   * @return completions
   */
  public StringList complete() {
    final StringList sl = new StringList();
    if(show) {
      for(final PathNode n : curr) {
        final String nm = string(n.token(data));
        if(!nm.isEmpty() && !sl.contains(nm)) sl.add(nm);
      }
      sl.sort(true, true);
    }
    return sl;
  }

  @Override
  protected void checkInit() {
    if(stack != null && !stack.empty()) return;
    all = data.paths.root();
    curr = all;
    stack = new Stack<ArrayList<PathNode>>();
  }

  @Override
  protected void checkAxis(final Axis axis) {
    all = axis != Axis.CHILD && axis != Axis.DESC ?
      new ArrayList<PathNode>() : PathSummary.desc(curr, axis == Axis.DESC);
    curr = all;
    show = true;
  }

  @Override
  protected void checkTest(final Test test, final boolean attr) {
    final TokenBuilder tb = new TokenBuilder();
    if(attr) tb.add('@');
    if(test != null) tb.add(test.toString().replaceAll("\\*:", ""));
    tag = tb.finish();
    // use inexact matching only, if the tag is at the end:
    checkTest(ip < il);
  }

  /**
   * Checks the tag name.
   * @param eq equality test
   */
  private void checkTest(final boolean eq) {
    if(tag == null) return;

    final ArrayList<PathNode> tmp = new ArrayList<PathNode>();
    boolean s = false;
    for(final PathNode p : all) {
      final byte[] nm = p.token(data);
      if(startsWith(nm, tag)) {
        if(!eq || eq(nm, tag)) tmp.add(p);
        s |= !eq(tag, nm);
      }
    }
    show = tag.length == 0 || s;
    curr = tmp;
  }

  @Override
  protected void checkPred(final boolean open) {
    if(stack == null) return;
    if(open) {
      checkTest(true);
      final ArrayList<PathNode> tmp = new ArrayList<PathNode>();
      for(final PathNode p : curr) tmp.add(p);
      stack.add(tmp);
      checkAxis(Axis.CHILD);
    } else {
      curr = stack.pop();
      show = false;
      all = curr;
    }
  }

  @Override
  public QueryException error(final Err err, final Object... arg) throws QueryException {
    throw new QueryException(info(), err, arg).suggest(this, complete());
  }
}
