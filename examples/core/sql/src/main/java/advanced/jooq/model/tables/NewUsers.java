/*
 * This file is generated by jOOQ.
 */
package advanced.jooq.model.tables;


import advanced.jooq.model.DefaultSchema;
import advanced.jooq.model.Keys;
import advanced.jooq.model.tables.records.NewUsersRecord;

import java.util.Collection;

import org.jooq.Condition;
import org.jooq.Field;
import org.jooq.Identity;
import org.jooq.Name;
import org.jooq.PlainSQL;
import org.jooq.QueryPart;
import org.jooq.SQL;
import org.jooq.Schema;
import org.jooq.Select;
import org.jooq.Stringly;
import org.jooq.Table;
import org.jooq.TableField;
import org.jooq.TableOptions;
import org.jooq.UniqueKey;
import org.jooq.impl.DSL;
import org.jooq.impl.SQLDataType;
import org.jooq.impl.TableImpl;


/**
 * This class is generated by jOOQ.
 */
@SuppressWarnings({ "all", "unchecked", "rawtypes", "this-escape" })
public class NewUsers extends TableImpl<NewUsersRecord> {

    private static final long serialVersionUID = 1L;

    /**
     * The reference instance of <code>new_users</code>
     */
    public static final NewUsers NEW_USERS = new NewUsers();

    /**
     * The class holding records for this type
     */
    @Override
    public Class<NewUsersRecord> getRecordType() {
        return NewUsersRecord.class;
    }

    /**
     * The column <code>new_users.id</code>.
     */
    public final TableField<NewUsersRecord, Integer> ID = createField(DSL.name("id"), SQLDataType.INTEGER.nullable(false).identity(true), this, "");

    /**
     * The column <code>new_users.first_name</code>.
     */
    public final TableField<NewUsersRecord, String> FIRST_NAME = createField(DSL.name("first_name"), SQLDataType.VARCHAR(128).nullable(false), this, "");

    /**
     * The column <code>new_users.last_name</code>.
     */
    public final TableField<NewUsersRecord, String> LAST_NAME = createField(DSL.name("last_name"), SQLDataType.VARCHAR(128).nullable(false), this, "");

    private NewUsers(Name alias, Table<NewUsersRecord> aliased) {
        this(alias, aliased, (Field<?>[]) null, null);
    }

    private NewUsers(Name alias, Table<NewUsersRecord> aliased, Field<?>[] parameters, Condition where) {
        super(alias, null, aliased, parameters, DSL.comment(""), TableOptions.table(), where);
    }

    /**
     * Create an aliased <code>new_users</code> table reference
     */
    public NewUsers(String alias) {
        this(DSL.name(alias), NEW_USERS);
    }

    /**
     * Create an aliased <code>new_users</code> table reference
     */
    public NewUsers(Name alias) {
        this(alias, NEW_USERS);
    }

    /**
     * Create a <code>new_users</code> table reference
     */
    public NewUsers() {
        this(DSL.name("new_users"), null);
    }

    @Override
    public Schema getSchema() {
        return aliased() ? null : DefaultSchema.DEFAULT_SCHEMA;
    }

    @Override
    public Identity<NewUsersRecord, Integer> getIdentity() {
        return (Identity<NewUsersRecord, Integer>) super.getIdentity();
    }

    @Override
    public UniqueKey<NewUsersRecord> getPrimaryKey() {
        return Keys.CONSTRAINT_F;
    }

    @Override
    public NewUsers as(String alias) {
        return new NewUsers(DSL.name(alias), this);
    }

    @Override
    public NewUsers as(Name alias) {
        return new NewUsers(alias, this);
    }

    @Override
    public NewUsers as(Table<?> alias) {
        return new NewUsers(alias.getQualifiedName(), this);
    }

    /**
     * Rename this table
     */
    @Override
    public NewUsers rename(String name) {
        return new NewUsers(DSL.name(name), null);
    }

    /**
     * Rename this table
     */
    @Override
    public NewUsers rename(Name name) {
        return new NewUsers(name, null);
    }

    /**
     * Rename this table
     */
    @Override
    public NewUsers rename(Table<?> name) {
        return new NewUsers(name.getQualifiedName(), null);
    }

    /**
     * Create an inline derived table from this table
     */
    @Override
    public NewUsers where(Condition condition) {
        return new NewUsers(getQualifiedName(), aliased() ? this : null, null, condition);
    }

    /**
     * Create an inline derived table from this table
     */
    @Override
    public NewUsers where(Collection<? extends Condition> conditions) {
        return where(DSL.and(conditions));
    }

    /**
     * Create an inline derived table from this table
     */
    @Override
    public NewUsers where(Condition... conditions) {
        return where(DSL.and(conditions));
    }

    /**
     * Create an inline derived table from this table
     */
    @Override
    public NewUsers where(Field<Boolean> condition) {
        return where(DSL.condition(condition));
    }

    /**
     * Create an inline derived table from this table
     */
    @Override
    @PlainSQL
    public NewUsers where(SQL condition) {
        return where(DSL.condition(condition));
    }

    /**
     * Create an inline derived table from this table
     */
    @Override
    @PlainSQL
    public NewUsers where(@Stringly.SQL String condition) {
        return where(DSL.condition(condition));
    }

    /**
     * Create an inline derived table from this table
     */
    @Override
    @PlainSQL
    public NewUsers where(@Stringly.SQL String condition, Object... binds) {
        return where(DSL.condition(condition, binds));
    }

    /**
     * Create an inline derived table from this table
     */
    @Override
    @PlainSQL
    public NewUsers where(@Stringly.SQL String condition, QueryPart... parts) {
        return where(DSL.condition(condition, parts));
    }

    /**
     * Create an inline derived table from this table
     */
    @Override
    public NewUsers whereExists(Select<?> select) {
        return where(DSL.exists(select));
    }

    /**
     * Create an inline derived table from this table
     */
    @Override
    public NewUsers whereNotExists(Select<?> select) {
        return where(DSL.notExists(select));
    }
}
