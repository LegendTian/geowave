package mil.nga.giat.geowave.core.store.query;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;

import org.apache.log4j.Logger;

import com.google.common.math.DoubleMath;

import mil.nga.giat.geowave.core.index.NumericIndexStrategy;
import mil.nga.giat.geowave.core.index.StringUtils;
import mil.nga.giat.geowave.core.index.dimension.NumericDimensionDefinition;
import mil.nga.giat.geowave.core.index.sfc.data.BasicNumericDataset;
import mil.nga.giat.geowave.core.index.sfc.data.MultiDimensionalNumericData;
import mil.nga.giat.geowave.core.index.sfc.data.NumericData;
import mil.nga.giat.geowave.core.index.sfc.data.NumericRange;
import mil.nga.giat.geowave.core.store.dimension.DimensionField;
import mil.nga.giat.geowave.core.store.filter.BasicQueryFilter;
import mil.nga.giat.geowave.core.store.filter.DistributableFilterList;
import mil.nga.giat.geowave.core.store.filter.DistributableQueryFilter;
import mil.nga.giat.geowave.core.store.filter.QueryFilter;
import mil.nga.giat.geowave.core.store.index.CommonIndexModel;
import mil.nga.giat.geowave.core.store.index.CommonIndexValue;
import mil.nga.giat.geowave.core.store.index.Index;

/**
 * The Basic Query class represent a hyper-cube(s) query across all dimensions
 * that match the Constraints passed into the constructor
 */
public class BasicQuery implements
		DistributableQuery
{
	private final static double DOUBLE_TOLERANCE = 1E-12d;
	private final static Logger LOGGER = Logger.getLogger(BasicQuery.class);

	/**
	 * 
	 * A set of constraints, one range per dimension
	 * 
	 */
	public static class ConstraintSet
	{
		protected Map<Class<? extends NumericDimensionDefinition>, ConstraintData> constraintsPerTypeOfDimensionDefinition;

		public ConstraintSet() {
			constraintsPerTypeOfDimensionDefinition = new LinkedHashMap<Class<? extends NumericDimensionDefinition>, ConstraintData>();
		}

		public ConstraintSet(
				final Map<Class<? extends NumericDimensionDefinition>, ConstraintData> constraintsPerTypeOfDimensionDefinition ) {
			this.constraintsPerTypeOfDimensionDefinition = constraintsPerTypeOfDimensionDefinition;
		}

		public ConstraintSet(
				final Class<? extends NumericDimensionDefinition> dimDefinition,
				final ConstraintData constraintData ) {
			this();
			addConstraint(
					dimDefinition,
					constraintData);
		}

		public void addConstraint(
				final Class<? extends NumericDimensionDefinition> dimDefinition,
				final ConstraintData constraintData ) {
			final ConstraintData myCd = constraintsPerTypeOfDimensionDefinition.get(dimDefinition);
			if (myCd != null) {
				constraintsPerTypeOfDimensionDefinition.put(
						dimDefinition,
						myCd.merge(constraintData));
			}
			else {
				constraintsPerTypeOfDimensionDefinition.put(
						dimDefinition,
						constraintData);
			}
		}

		public ConstraintSet merge(
				final ConstraintSet constraintSet ) {
			final Map<Class<? extends NumericDimensionDefinition>, ConstraintData> newSet = new LinkedHashMap<Class<? extends NumericDimensionDefinition>, ConstraintData>();

			for (final Map.Entry<Class<? extends NumericDimensionDefinition>, ConstraintData> entry : constraintSet.constraintsPerTypeOfDimensionDefinition.entrySet()) {
				final ConstraintData data = constraintsPerTypeOfDimensionDefinition.get(entry.getKey());

				if (data == null) {
					newSet.put(
							entry.getKey(),
							entry.getValue());
				}
				else {
					newSet.put(
							entry.getKey(),
							data.merge(entry.getValue()));

				}
			}
			for (final Map.Entry<Class<? extends NumericDimensionDefinition>, ConstraintData> entry : constraintsPerTypeOfDimensionDefinition.entrySet()) {
				final ConstraintData data = constraintSet.constraintsPerTypeOfDimensionDefinition.get(entry.getKey());

				if (data == null) {
					newSet.put(
							entry.getKey(),
							entry.getValue());
				}
			}
			return new ConstraintSet(
					newSet);
		}

		public boolean isEmpty() {
			return constraintsPerTypeOfDimensionDefinition.isEmpty();
		}

		public boolean matches(
				final ConstraintSet constraints ) {
			if (constraints.isEmpty() != isEmpty()) {
				return false;
			}
			for (final Map.Entry<Class<? extends NumericDimensionDefinition>, ConstraintData> entry : constraintsPerTypeOfDimensionDefinition.entrySet()) {
				final ConstraintData data = constraints.constraintsPerTypeOfDimensionDefinition.get(entry.getKey());
				if ((data == null) || !data.matches(entry.getValue())) {
					return false;
				}
			}
			return true;
		}

		/**
		 * 
		 * @param constraints
		 * @return true if all dimensions intersect
		 */
		public boolean intersects(
				final ConstraintSet constraints ) {
			if (constraints.isEmpty() != isEmpty()) {
				return true;
			}
			boolean intersects = true;
			for (final Map.Entry<Class<? extends NumericDimensionDefinition>, ConstraintData> entry : constraintsPerTypeOfDimensionDefinition.entrySet()) {
				final ConstraintData data = constraints.constraintsPerTypeOfDimensionDefinition.get(entry.getKey());
				intersects &= ((data != null) && data.intersects(entry.getValue()));
			}
			return intersects;
		}

		public MultiDimensionalNumericData getIndexConstraints(
				final NumericIndexStrategy indexStrategy ) {
			if (constraintsPerTypeOfDimensionDefinition.isEmpty()) {
				return new BasicNumericDataset();
			}
			final NumericDimensionDefinition[] dimensionDefinitions = indexStrategy.getOrderedDimensionDefinitions();
			final NumericData[] dataPerDimension = new NumericData[dimensionDefinitions.length];
			// all or nothing...for now
			for (int d = 0; d < dimensionDefinitions.length; d++) {
				final ConstraintData dimConstraint = constraintsPerTypeOfDimensionDefinition.get(dimensionDefinitions[d].getClass());
				dataPerDimension[d] = (dimConstraint == null ? null : dimConstraint.range);
			}
			return new BasicNumericDataset(
					dataPerDimension);
		}

		public boolean isSupported(
				final Index index ) {
			final DimensionField<? extends CommonIndexValue>[] fields = index.getIndexModel().getDimensions();
			final Set<Class<? extends NumericDimensionDefinition>> fieldTypeSet = new HashSet<Class<? extends NumericDimensionDefinition>>();
			// first create a set of the field's base definition types that are
			// within the index model
			for (final DimensionField<? extends CommonIndexValue> field : fields) {
				fieldTypeSet.add(field.getBaseDefinition().getClass());
			}
			// then ensure each of the definition types that is required by
			// these
			// constraints are in the index model
			for (final Map.Entry<Class<? extends NumericDimensionDefinition>, ConstraintData> entry : constraintsPerTypeOfDimensionDefinition.entrySet()) {
				// ** defaults are not mandatory **
				if (!fieldTypeSet.contains(entry.getKey()) && !entry.getValue().isDefault) {
					return false;
				}
			}
			return true;
		}

		protected DistributableQueryFilter createFilter(
				final CommonIndexModel indexModel,
				final BasicQuery basicQuery ) {
			final DimensionField<?>[] dimensionFields = indexModel.getDimensions();
			final NumericData[] orderedConstraintsPerDimension = new NumericData[dimensionFields.length];
			for (int d = 0; d < dimensionFields.length; d++) {
				final ConstraintData nd = constraintsPerTypeOfDimensionDefinition.get(dimensionFields[d].getBaseDefinition().getClass());
				if (nd == null) {
					orderedConstraintsPerDimension[d] = dimensionFields[d].getBaseDefinition().getFullRange();
				}
				else {
					orderedConstraintsPerDimension[d] = constraintsPerTypeOfDimensionDefinition.get(dimensionFields[d].getBaseDefinition().getClass()).range;
				}
			}
			return basicQuery.createQueryFilter(
					new BasicNumericDataset(
							orderedConstraintsPerDimension),
					dimensionFields);
		}

		public byte[] toBinary() {
			final List<byte[]> bytes = new ArrayList<byte[]>(
					constraintsPerTypeOfDimensionDefinition.size());
			int totalBytes = 4;
			for (final Entry<Class<? extends NumericDimensionDefinition>, ConstraintData> c : constraintsPerTypeOfDimensionDefinition.entrySet()) {
				final byte[] className = StringUtils.stringToBinary(c.getKey().getName());
				final double min = c.getValue().range.getMin();
				final double max = c.getValue().range.getMax();
				final int entryLength = className.length + 22;
				final short isDefault = (short) (c.getValue().isDefault ? 1 : 0);
				final ByteBuffer entryBuf = ByteBuffer.allocate(entryLength);
				entryBuf.putInt(className.length);
				entryBuf.put(className);
				entryBuf.putDouble(min);
				entryBuf.putDouble(max);
				entryBuf.putShort(isDefault);
				bytes.add(entryBuf.array());
				totalBytes += entryLength;
			}

			final ByteBuffer buf = ByteBuffer.allocate(totalBytes);
			buf.putInt(bytes.size());
			for (final byte[] entryBytes : bytes) {
				buf.put(entryBytes);
			}
			return buf.array();
		}

		public void fromBinary(
				final byte[] bytes ) {
			final ByteBuffer buf = ByteBuffer.wrap(bytes);
			final int numEntries = buf.getInt();
			final Map<Class<? extends NumericDimensionDefinition>, ConstraintData> constraintsPerTypeOfDimensionDefinition = new LinkedHashMap<Class<? extends NumericDimensionDefinition>, ConstraintData>(
					numEntries);
			for (int i = 0; i < numEntries; i++) {
				final int classNameLength = buf.getInt();
				final byte[] className = new byte[classNameLength];
				buf.get(className);
				final double min = buf.getDouble();
				final double max = buf.getDouble();
				final boolean isDefault = buf.getShort() > 0;
				final String classNameStr = StringUtils.stringFromBinary(className);
				try {
					final Class<? extends NumericDimensionDefinition> cls = (Class<? extends NumericDimensionDefinition>) Class.forName(classNameStr);
					constraintsPerTypeOfDimensionDefinition.put(
							cls,
							new ConstraintData(
									new NumericRange(
											min,
											max),
									isDefault));
				}
				catch (final ClassNotFoundException e) {
					LOGGER.warn(
							"Cannot find dimension definition class: " + classNameStr,
							e);
				}
			}
			this.constraintsPerTypeOfDimensionDefinition = constraintsPerTypeOfDimensionDefinition;
		}

	}

	public static class ConstraintData
	{
		protected NumericData range;
		protected boolean isDefault;

		public ConstraintData(
				final NumericData range,
				final boolean isDefault ) {
			super();
			this.range = range;
			this.isDefault = isDefault;
		}

		public boolean intersects(
				final ConstraintData cd ) {
			final double i1 = cd.range.getMin();
			final double i2 = cd.range.getMax();
			final double j1 = range.getMin();
			final double j2 = range.getMax();
			return ((i1 < j2) || DoubleMath.fuzzyEquals(
					i1,
					j2,
					DOUBLE_TOLERANCE)) && ((i2 > j1) || DoubleMath.fuzzyEquals(
					i2,
					j1,
					DOUBLE_TOLERANCE));
		}

		public ConstraintData merge(
				final ConstraintData cd ) {
			if (range.equals(cd.range)) {
				return new ConstraintData(
						range,
						isDefault);
			}
			return new ConstraintData(
					new NumericRange(
							Math.min(
									cd.range.getMin(),
									range.getMin()),
							Math.max(
									cd.range.getMax(),
									range.getMax())),
					false); // TODO: ideally, this would be set based on some
							// logic
		}

		@Override
		public int hashCode() {
			final int prime = 31;
			int result = 1;
			result = (prime * result) + (isDefault ? 1231 : 1237);
			result = (prime * result) + ((range == null) ? 0 : range.hashCode());
			return result;
		}

		@Override
		public boolean equals(
				final Object obj ) {
			if (this == obj) {
				return true;
			}
			if (obj == null) {
				return false;
			}
			if (getClass() != obj.getClass()) {
				return false;
			}
			final ConstraintData other = (ConstraintData) obj;
			if (isDefault != other.isDefault) {
				return false;
			}
			if (range == null) {
				if (other.range != null) {
					return false;
				}
			}
			else if (!range.equals(other.range)) {
				return false;
			}
			return true;
		}

		/**
		 * Ignores 'default' indicator
		 * 
		 * @param other
		 * @return
		 */
		public boolean matches(
				final ConstraintData other ) {
			if (this == other) {
				return true;
			}

			if (range == null) {
				if (other.range != null) {
					return false;
				}
			}
			else if (!DoubleMath.fuzzyEquals(
					range.getMin(),
					other.range.getMin(),
					DOUBLE_TOLERANCE) || !DoubleMath.fuzzyEquals(
					range.getMax(),
					other.range.getMax(),
					DOUBLE_TOLERANCE)) {
				return false;
			}
			return true;
		}
	}

	/**
	 * 
	 * A list of Constraint Sets. Each Constraint Set is an individual
	 * hyper-cube query.
	 * 
	 */
	public static class Constraints
	{
		// these basic queries are tied to NumericDimensionDefinition types, not
		// ideal, but third-parties can and will nned to implement their own
		// queries if they implement their own dimension definitions
		private List<ConstraintSet> constraintsSets = new LinkedList<ConstraintSet>();

		public Constraints() {}

		public Constraints(
				final ConstraintSet constraintSet ) {
			constraintsSets.add(constraintSet);
		}

		public Constraints(
				final List<ConstraintSet> constraintSets ) {
			constraintsSets.addAll(constraintSets);
		}

		public Constraints merge(
				final Constraints constraints ) {
			return merge(constraints.constraintsSets);
		}

		public Constraints merge(
				final List<ConstraintSet> otherConstraintSets ) {

			if (otherConstraintSets.isEmpty()) return this;
			final List<ConstraintSet> newSets = new LinkedList<ConstraintSet>();

			for (final ConstraintSet newSet : otherConstraintSets) {
				add(
						newSets,
						constraintsSets,
						newSet);
			}
			return new Constraints(
					newSets);
		}

		private static void add(
				final List<ConstraintSet> newSets,
				final List<ConstraintSet> currentSets,
				final ConstraintSet newSet ) {
			for (final ConstraintSet cs : currentSets) {
				newSets.add(cs.merge(newSet));
			}
		}

		public boolean isEmpty() {
			return constraintsSets.isEmpty();
		}

		public boolean matches(
				final Constraints constraints ) {
			if (constraints.isEmpty() != isEmpty()) {
				return false;
			}
			for (ConstraintSet set : this.constraintsSets) {
				boolean foundMatch = false;
				for (ConstraintSet otherSet : constraints.constraintsSets) {
					foundMatch |= set.matches(otherSet);
				}
				if (!foundMatch) return false;
			}
			return true;
		}

		@Override
		public int hashCode() {
			final int prime = 31;
			int result = 1;
			result = prime * result + ((constraintsSets == null) ? 0 : constraintsSets.hashCode());
			return result;
		}

		@Override
		public boolean equals(
				Object obj ) {
			if (this == obj) return true;
			if (obj == null) return false;
			if (getClass() != obj.getClass()) return false;
			Constraints other = (Constraints) obj;
			if (constraintsSets == null) {
				if (other.constraintsSets != null) return false;
			}
			else if (!constraintsSets.equals(other.constraintsSets)) return false;
			return true;
		}

		public List<MultiDimensionalNumericData> getIndexConstraints(
				final NumericIndexStrategy indexStrategy ) {
			if (constraintsSets.isEmpty()) {
				return Collections.emptyList();
			}
			final List<MultiDimensionalNumericData> setRanges = new ArrayList<MultiDimensionalNumericData>(
					constraintsSets.size());
			for (final ConstraintSet set : constraintsSets) {
				setRanges.add(set.getIndexConstraints(indexStrategy));
			}
			return setRanges;
		}

		/**
		 * 
		 * @param index
		 * @return true if all constrain sets match the index
		 * 
		 *         TODO: Should we allow each constraint target each index?
		 */
		public boolean isSupported(
				final Index index ) {
			for (final ConstraintSet set : constraintsSets) {
				if (!set.isSupported(index)) {
					return false;
				}
			}
			return true;
		}
	}

	private Constraints constraints;

	protected BasicQuery() {}

	public BasicQuery(
			final Constraints constraints ) {
		this.constraints = constraints;
	}

	@Override
	public List<QueryFilter> createFilters(
			final CommonIndexModel indexModel ) {
		final List<DistributableQueryFilter> filters = new ArrayList<DistributableQueryFilter>();
		for (final ConstraintSet constraint : constraints.constraintsSets) {
			final DistributableQueryFilter filter = constraint.createFilter(
					indexModel,
					this);
			if (filter != null) filters.add(filter);
		}
		if (!filters.isEmpty()) {
			return Collections.<QueryFilter> singletonList(new DistributableFilterList(
					false,
					filters));
		}
		return Collections.emptyList();
	}

	protected DistributableQueryFilter createQueryFilter(
			final MultiDimensionalNumericData constraints,
			final DimensionField<?>[] dimensionFields ) {
		return new BasicQueryFilter(
				constraints,
				dimensionFields);
	}

	@Override
	public boolean isSupported(
			final Index index ) {
		return constraints.isSupported(index);
	}

	@Override
	public List<MultiDimensionalNumericData> getIndexConstraints(
			final NumericIndexStrategy indexStrategy ) {
		return constraints.getIndexConstraints(indexStrategy);
	}

	@Override
	public byte[] toBinary() {
		final List<byte[]> bytes = new ArrayList<byte[]>(
				constraints.constraintsSets.size());
		int totalBytes = 4;
		for (final ConstraintSet c : constraints.constraintsSets) {
			bytes.add(c.toBinary());
			totalBytes += (bytes.get(bytes.size() - 1).length + 4);
		}

		final ByteBuffer buf = ByteBuffer.allocate(totalBytes);
		buf.putInt(bytes.size());
		for (final byte[] entryBytes : bytes) {
			buf.putInt(entryBytes.length);
			buf.put(entryBytes);
		}
		return buf.array();
	}

	@Override
	public void fromBinary(
			final byte[] bytes ) {
		final ByteBuffer buf = ByteBuffer.wrap(bytes);
		final int numEntries = buf.getInt();
		final List<ConstraintSet> sets = new LinkedList<ConstraintSet>();
		for (int i = 0; i < numEntries; i++) {
			final byte[] d = new byte[buf.getInt()];
			buf.get(d);
			final ConstraintSet cs = new ConstraintSet();
			cs.fromBinary(d);
			sets.add(cs);
		}
		constraints = new Constraints(
				sets);
	}
}