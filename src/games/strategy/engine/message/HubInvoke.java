package games.strategy.engine.message;

import games.strategy.net.GUID;

public class HubInvoke extends Invoke {
  public HubInvoke() {
    super();
  }

  public HubInvoke(final GUID methodCallID, final boolean needReturnValues, final RemoteMethodCall call) {
    super(methodCallID, needReturnValues, call);
  }
}
