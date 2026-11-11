package dev.danilocordeiro.ledgermcp.audit;

import java.sql.Connection;
import java.sql.SQLException;
import org.h2.api.Trigger;

/** H2 counterpart of the PostgreSQL trigger: refuses UPDATE and DELETE on audit_event. */
public class H2AppendOnlyTrigger implements Trigger {

    @Override
    public void fire(Connection conn, Object[] oldRow, Object[] newRow) throws SQLException {
        throw new SQLException("audit_event is append-only");
    }
}
