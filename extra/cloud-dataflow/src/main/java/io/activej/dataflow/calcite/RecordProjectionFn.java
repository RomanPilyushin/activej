package io.activej.dataflow.calcite;

import io.activej.codegen.expression.Expression;
import io.activej.common.Checks;
import io.activej.dataflow.calcite.where.Operand;
import io.activej.record.Record;
import io.activej.record.RecordProjection;
import io.activej.record.RecordScheme;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BiFunction;
import java.util.function.Function;
import java.util.function.UnaryOperator;

import static io.activej.codegen.expression.Expressions.call;
import static io.activej.codegen.expression.Expressions.value;
import static io.activej.common.Checks.checkArgument;

public final class RecordProjectionFn implements Function<Record, Record> {
	private static final boolean CHECK = Checks.isEnabled(RecordProjectionFn.class);

	private final List<FieldProjection> fieldProjections;

	private @Nullable RecordProjection projection;

	public RecordProjectionFn(List<FieldProjection> fieldProjections) {
		this.fieldProjections = new ArrayList<>(fieldProjections);
	}

	@Override
	public Record apply(Record record) {
		if (projection == null) {
			RecordScheme original = record.getScheme();
			Map<String, UnaryOperator<Expression>> mapping = new HashMap<>();
			RecordScheme schemeTo = getToScheme(original, mapping::put);
			projection = RecordProjection.projection(original, schemeTo, mapping);
		}

		if (CHECK) {
			checkArgument(record.getScheme().equals(projection.getSchemeFrom()));
		}

		return projection.apply(record);
	}

	public RecordProjectionFn materialize(List<Object> params) {
		return new RecordProjectionFn(
				fieldProjections.stream()
						.map(fieldProjection -> new FieldProjection(fieldProjection.operand().materialize(params), fieldProjection.fieldName()))
						.toList());
	}

	public RecordScheme getToScheme(RecordScheme original, BiFunction<String, UnaryOperator<Expression>, @Nullable UnaryOperator<Expression>> mapping) {
		RecordScheme schemeTo = RecordScheme.create(original.getClassLoader());
		for (FieldProjection fieldProjection : fieldProjections) {
			String fieldName = fieldProjection.fieldName() != null ? fieldProjection.fieldName() : fieldProjection.operand().getFieldName(original);
			schemeTo.withField(fieldName, fieldProjection.operand().getFieldType(original));
			UnaryOperator<Expression> previous = mapping.apply(fieldName, record -> call(value(fieldProjection.operand()), "getValue", record));
			if (previous != null) {
				throw new IllegalArgumentException();
			}
		}
		return schemeTo;
	}

	public List<FieldProjection> getFieldProjections() {
		return new ArrayList<>(fieldProjections);
	}

	public record FieldProjection(Operand operand, @Nullable String fieldName) {
	}
}
