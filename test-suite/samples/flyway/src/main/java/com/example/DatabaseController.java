package com.example;

import io.micronaut.http.annotation.Controller;
import io.micronaut.http.annotation.Get;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;

/** Reports what Flyway applied at startup and reads what the migrations wrote. */
@Controller("/")
public class DatabaseController {

    private final DataSource dataSource;

    /**
     * Creates the controller.
     *
     * @param dataSource the data source Flyway migrated
     */
    public DatabaseController(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    /**
     * The versions Flyway applied, in the order it applied them. It answers even when Flyway found none of the
     * application's migrations, so that a test learns that at once rather than through a failing query.
     *
     * @return the versions, separated by commas
     * @throws SQLException if the history cannot be read
     */
    @Get(uri = "/migrations", produces = "text/plain")
    public String migrations() throws SQLException {
        return String.join(",", column("select \"version\" from \"flyway_schema_history\" "
                + "where \"success\" and \"version\" is not null order by \"installed_rank\""));
    }

    /**
     * The genres the application's migrations created and filled.
     *
     * @return the genre names, separated by commas
     * @throws SQLException if the table does not exist
     */
    @Get(uri = "/genres", produces = "text/plain")
    public String genres() throws SQLException {
        return String.join(",", column("select name from genre order by id"));
    }

    /**
     * The review the dependency's migration created and filled.
     *
     * @return the review bodies, separated by commas
     * @throws SQLException if the table does not exist
     */
    @Get(uri = "/reviews", produces = "text/plain")
    public String reviews() throws SQLException {
        return String.join(",", column("select body from review order by id"));
    }

    private List<String> column(String query) throws SQLException {
        List<String> values = new ArrayList<>();
        try (Connection connection = dataSource.getConnection();
             Statement statement = connection.createStatement();
             ResultSet result = statement.executeQuery(query)) {
            while (result.next()) {
                values.add(result.getString(1));
            }
        }
        return values;
    }
}
