package org.basex.query.func;

import org.basex.query.*;
import org.basex.query.expr.*;
import org.basex.query.value.item.*;
import org.basex.util.*;

/**
 * Streaming functions.
 *
 * @author BaseX Team 2005-12, BSD License
 * @author Christian Gruen
 */
public final class FNStream extends StandardFunc {
  /**
   * Constructor.
   * @param ii input info
   * @param f function definition
   * @param e arguments
   */
  public FNStream(final InputInfo ii, final Function f, final Expr... e) {
    super(ii, f, e);
  }

  @Override
  public Item item(final QueryContext ctx, final InputInfo ii) throws QueryException {
    switch(sig) {
      case _STREAM_MATERIALIZE:   return materialize(ctx);
      case _STREAM_IS_STREAMABLE: return isStreamable(ctx);
      default:                    return super.item(ctx, ii);
    }
  }

  /**
   * Performs the materialize function.
   * @param ctx query context
   * @return resulting value
   * @throws QueryException query exception
   */
  private Item materialize(final QueryContext ctx) throws QueryException {
    return checkItem(expr[0], ctx).materialize(info);
  }

  /**
   * Performs the is-streamable function.
   * @param ctx query context
   * @return resulting value
   * @throws QueryException query exception
   */
  private Item isStreamable(final QueryContext ctx) throws QueryException {
    final Item it = checkItem(expr[0], ctx);
    return Bln.get(it instanceof StrStream || it instanceof B64Stream);
  }

  @Override
  Expr opt(final QueryContext ctx) throws QueryException {
    if(sig == Function._STREAM_MATERIALIZE) type = expr[0].type();
    return this;
  }
}
