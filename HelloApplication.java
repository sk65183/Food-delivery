package com.iu.demo1;

import javafx.application.Application;
import javafx.geometry.Insets;
import javafx.scene.Scene;
import javafx.scene.control.*;
import javafx.scene.layout.*;
import javafx.stage.Stage;

import java.sql.*;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;

public class HelloApplication extends Application {
    private Stage primaryStage;
    private static final String DB_URL = "jdbc:mysql://localhost:3306/organic_delivery";
    private static final String DB_USER = "root";
    private static final String DB_PASS = "sameer";

    private static Connection conn() throws SQLException {
        return DriverManager.getConnection(DB_URL, DB_USER, DB_PASS);
    }

    @Override
    public void start(Stage primaryStage) {
        this.primaryStage = primaryStage;
        showLoginScreen();
    }

    private void showLoginScreen() {
        GridPane gp = new GridPane();
        gp.setPadding(new Insets(20)); gp.setVgap(10); gp.setHgap(10);

        TextField userField = new TextField();
        PasswordField passField = new PasswordField();
        Label msg = new Label();
        Button login = new Button("Login");
        login.setStyle("-fx-background-color:#4CAF50;-fx-text-fill:white;");
        gp.add(new Label("Username:"), 0, 0); gp.add(userField, 1, 0);
        gp.add(new Label("Password:"), 0, 1); gp.add(passField, 1, 1);
        gp.add(login, 1, 2); gp.add(msg, 1, 3);

        login.setOnAction(e -> {
            if ("admin".equals(userField.getText()) && "password".equals(passField.getText())) {
                showMainMenu();
            } else msg.setText("Invalid credentials");
        });

        primaryStage.setTitle("Login");
        primaryStage.setScene(new Scene(gp, 350, 200));
        primaryStage.show();
    }

    private void showMainMenu() {
        VBox vb = new VBox(10);
        vb.setPadding(new Insets(20));

        Button place = new Button("Place Order");
        Button view = new Button("View Orders");
        Button dash = new Button("Dashboard");
        Button logout = new Button("Logout");

        place.setStyle("-fx-background-color:#2196F3;-fx-text-fill:white;");
        view.setStyle(place.getStyle());
        dash.setStyle(place.getStyle());
        logout.setStyle("-fx-background-color:#f44336;-fx-text-fill:white;");

        place.setOnAction(e -> placeOrder());
        view.setOnAction(e -> viewOrders());
        dash.setOnAction(e -> showDashboard());
        logout.setOnAction(e -> showLoginScreen());

        vb.getChildren().addAll(new Label("Admin Panel"), place, view, dash, logout);
        primaryStage.setScene(new Scene(vb, 350, 300));
    }

    private void placeOrder() {
        VBox vb = new VBox(10);
        vb.setPadding(new Insets(20));

        TextField cn = new TextField();
        TextField rn = new TextField();
        vb.getChildren().addAll(new Label("Customer Name:"), cn, new Label("Rider Name:"), rn);

        Map<String, Integer> inventory = new LinkedHashMap<>();
        Map<String, TextField> qtyFields = new LinkedHashMap<>();

        // Load inventory
        try (Connection c = conn();
             ResultSet rs = c.createStatement()
                     .executeQuery("SELECT name,quantity,price FROM inventory")) {
            while (rs.next()) {
                String name = rs.getString("name");
                int qty = rs.getInt("quantity");
                double price = rs.getDouble("price");
                inventory.put(name, qty);

                Label lbl = new Label(name + " (Stock: " + qty + ", Price: $" + price + ")");
                TextField tf = new TextField();
                tf.setPromptText("Qty");
                tf.setPrefWidth(60);
                qtyFields.put(name, tf);
                vb.getChildren().add(new HBox(10, lbl, tf));
            }
        } catch (SQLException ex) {
            ex.printStackTrace();
        }

        Label msg = new Label();
        Button submit = new Button("Submit Order");
        Button back = new Button("Back");
        submit.setStyle("-fx-background-color:#4CAF50;-fx-text-fill:white;");
        back.setStyle("-fx-background-color:gray;-fx-text-fill:white;");

        submit.setOnAction(e -> {
            String customerName = cn.getText().trim();
            String riderName = rn.getText().trim();

            if (customerName.isEmpty() || riderName.isEmpty()) {
                msg.setText("Name and Rider are required");
                return;
            }

            List<Map.Entry<String, Integer>> orderItems = new ArrayList<>();
            double total = 0;

            for (var entry : qtyFields.entrySet()) {
                String item = entry.getKey();
                String qtyText = entry.getValue().getText().trim();

                if (!qtyText.isEmpty()) {
                    try {
                        int qty = Integer.parseInt(qtyText);
                        int stock = inventory.get(item);
                        if (qty <= 0 || qty > stock) {
                            msg.setText("Invalid qty for " + item);
                            return;
                        }
                        orderItems.add(Map.entry(item, qty));
                    } catch (NumberFormatException ex) {
                        msg.setText("Enter numeric qty for " + item);
                        return;
                    }
                }
            }

            if (orderItems.isEmpty()) {
                msg.setText("No items selected");
                return;
            }

            // Insert into DB
            try (Connection c = conn()) {
                c.setAutoCommit(false);

                PreparedStatement pc = c.prepareStatement(
                        "INSERT INTO customers(name,delivered,rider_name,delivery_time) VALUES(?,?,?,?)",
                        Statement.RETURN_GENERATED_KEYS);
                pc.setString(1, customerName);
                pc.setBoolean(2, true);
                pc.setString(3, riderName);
                LocalDateTime dt = LocalDateTime.now().plusMinutes(30);
                pc.setTimestamp(4, Timestamp.valueOf(dt));
                pc.executeUpdate();
                int custId = pc.getGeneratedKeys().getInt(1);

                for (var oi : orderItems) {
                    PreparedStatement po = c.prepareStatement(
                            "INSERT INTO orders(customer_id,item_name,quantity) VALUES(?,?,?)");
                    po.setInt(1, custId);
                    po.setString(2, oi.getKey());
                    po.setInt(3, oi.getValue());
                    po.executeUpdate();

                    PreparedStatement pu = c.prepareStatement(
                            "UPDATE inventory SET quantity=quantity-? WHERE name=?");
                    pu.setInt(1, oi.getValue());
                    pu.setString(2, oi.getKey());
                    pu.executeUpdate();

                    total += oi.getValue() * getPrice(oi.getKey());
                }

                c.commit();

                Alert alert = new Alert(Alert.AlertType.INFORMATION);
                alert.setTitle("Order Placed");
                alert.setHeaderText("Total: $" + String.format("%.2f", total));
                alert.setContentText("Rider: " + riderName + "\nETA: " +
                        LocalDateTime.now().plusMinutes(30).format(DateTimeFormatter.ofPattern("HH:mm")));
                alert.showAndWait();

                showMainMenu();
            } catch (SQLException ex) {
                ex.printStackTrace();
                msg.setText("DB error: " + ex.getMessage());
            }
        });

        vb.getChildren().addAll(submit, msg, back);
        back.setOnAction(e -> showMainMenu());
        primaryStage.setScene(new Scene(vb, 450, 600));
    }

    private double getPrice(String itemName) throws SQLException {
        try (Connection c = conn();
             PreparedStatement ps = c.prepareStatement("SELECT price FROM inventory WHERE name = ?")) {
            ps.setString(1, itemName);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) return rs.getDouble("price");
            }
        }
        return 0;
    }

    private void viewOrders() {
        VBox vb = new VBox(10);
        vb.setPadding(new Insets(20));

        try (Connection c = conn();
             ResultSet rs = c.createStatement().executeQuery(
                     "SELECT c.name, o.item_name, o.quantity, c.rider_name, c.delivery_time " +
                             "FROM customers c JOIN orders o ON c.id = o.customer_id")) {
            while (rs.next()) {
                String line = String.format("%s: %s x%d → %s @ %s",
                        rs.getString(1), rs.getString(2), rs.getInt(3),
                        rs.getString(4),
                        rs.getTimestamp(5).toLocalDateTime()
                                .format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")));
                vb.getChildren().add(new Label(line));
            }
        } catch (SQLException e) {
            e.printStackTrace();
        }

        Button back = new Button("Back");
        back.setStyle("-fx-background-color:gray;-fx-text-fill:white;");
        back.setOnAction(e -> showMainMenu());

        vb.getChildren().add(back);
        primaryStage.setScene(new Scene(vb, 500, 500));
    }

    private void showDashboard() {
        VBox vb = new VBox(10);
        vb.setPadding(new Insets(20));
        vb.getChildren().add(new Label("📦 Current Inventory:"));

        try (Connection c = conn();
             ResultSet rs = c.createStatement()
                     .executeQuery("SELECT name,quantity,price FROM inventory")) {
            while (rs.next()) {
                vb.getChildren().add(new Label(
                        rs.getString("name") + " - " + rs.getInt("quantity") +
                                " in stock ($" + rs.getDouble("price") + ")"));
            }
        } catch (SQLException e) {
            e.printStackTrace();
        }

        Button back = new Button("Back");
        back.setStyle("-fx-background-color:gray;-fx-text-fill:white;");
        back.setOnAction(e -> showMainMenu());

        vb.getChildren().add(back);
        primaryStage.setScene(new Scene(vb, 500, 600));
    }

    public static void main(String[] args) {
        launch(args);
    }
}