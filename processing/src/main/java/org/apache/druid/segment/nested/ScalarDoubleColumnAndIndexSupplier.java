/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package org.apache.druid.segment.nested;

import com.google.common.base.Strings;
import com.google.common.base.Supplier;
import com.google.common.primitives.Doubles;
import it.unimi.dsi.fastutil.doubles.DoubleArraySet;
import it.unimi.dsi.fastutil.doubles.DoubleIterator;
import it.unimi.dsi.fastutil.doubles.DoubleSet;
import it.unimi.dsi.fastutil.ints.IntIntPair;
import it.unimi.dsi.fastutil.ints.IntIterator;
import org.apache.druid.collections.bitmap.BitmapFactory;
import org.apache.druid.collections.bitmap.ImmutableBitmap;
import org.apache.druid.java.util.common.RE;
import org.apache.druid.java.util.common.StringUtils;
import org.apache.druid.java.util.common.io.smoosh.SmooshedFileMapper;
import org.apache.druid.query.BitmapResultFactory;
import org.apache.druid.query.filter.DruidDoublePredicate;
import org.apache.druid.query.filter.DruidPredicateFactory;
import org.apache.druid.segment.IntListUtils;
import org.apache.druid.segment.column.BitmapColumnIndex;
import org.apache.druid.segment.column.ColumnBuilder;
import org.apache.druid.segment.column.ColumnIndexSupplier;
import org.apache.druid.segment.column.ColumnType;
import org.apache.druid.segment.column.DictionaryEncodedStringValueIndex;
import org.apache.druid.segment.column.DictionaryEncodedValueIndex;
import org.apache.druid.segment.column.DruidPredicateIndex;
import org.apache.druid.segment.column.NullValueIndex;
import org.apache.druid.segment.column.NumericRangeIndex;
import org.apache.druid.segment.column.SimpleBitmapColumnIndex;
import org.apache.druid.segment.column.SimpleImmutableBitmapIndex;
import org.apache.druid.segment.column.SimpleImmutableBitmapIterableIndex;
import org.apache.druid.segment.column.StringValueSetIndex;
import org.apache.druid.segment.data.BitmapSerdeFactory;
import org.apache.druid.segment.data.ColumnarDoubles;
import org.apache.druid.segment.data.CompressedColumnarDoublesSuppliers;
import org.apache.druid.segment.data.FixedIndexed;
import org.apache.druid.segment.data.GenericIndexed;
import org.apache.druid.segment.data.VByte;
import org.apache.druid.segment.serde.NestedCommonFormatColumnPartSerde;

import javax.annotation.Nullable;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Iterator;
import java.util.NoSuchElementException;
import java.util.SortedSet;

public class ScalarDoubleColumnAndIndexSupplier implements Supplier<NestedCommonFormatColumn>, ColumnIndexSupplier
{
  public static ScalarDoubleColumnAndIndexSupplier read(
      ByteOrder byteOrder,
      BitmapSerdeFactory bitmapSerdeFactory,
      ByteBuffer bb,
      ColumnBuilder columnBuilder
  )
  {
    final byte version = bb.get();
    final int columnNameLength = VByte.readInt(bb);
    final String columnName = StringUtils.fromUtf8(bb, columnNameLength);

    if (version == NestedCommonFormatColumnSerializer.V0) {
      try {

        final SmooshedFileMapper mapper = columnBuilder.getFileMapper();

        final ByteBuffer doubleDictionaryBuffer = NestedCommonFormatColumnPartSerde.loadInternalFile(
            mapper,
            columnName,
            NestedCommonFormatColumnSerializer.DOUBLE_DICTIONARY_FILE_NAME
        );
        final ByteBuffer doublesValueColumn = NestedCommonFormatColumnPartSerde.loadInternalFile(
            mapper,
            columnName,
            NestedCommonFormatColumnSerializer.DOUBLE_VALUE_COLUMN_FILE_NAME
        );

        final Supplier<FixedIndexed<Double>> doubleDictionarySupplier = FixedIndexed.read(
            doubleDictionaryBuffer,
            ColumnType.DOUBLE.getStrategy(),
            byteOrder,
            Double.BYTES
        );

        final Supplier<ColumnarDoubles> doubles = CompressedColumnarDoublesSuppliers.fromByteBuffer(
            doublesValueColumn,
            byteOrder
        );
        final ByteBuffer valueIndexBuffer = NestedCommonFormatColumnPartSerde.loadInternalFile(
            mapper,
            columnName,
            NestedCommonFormatColumnSerializer.BITMAP_INDEX_FILE_NAME
        );
        GenericIndexed<ImmutableBitmap> rBitmaps = GenericIndexed.read(
            valueIndexBuffer,
            bitmapSerdeFactory.getObjectStrategy(),
            columnBuilder.getFileMapper()
        );
        return new ScalarDoubleColumnAndIndexSupplier(
            doubleDictionarySupplier,
            doubles,
            rBitmaps,
            bitmapSerdeFactory.getBitmapFactory()
        );
      }
      catch (IOException ex) {
        throw new RE(ex, "Failed to deserialize V%s column.", version);
      }
    } else {
      throw new RE("Unknown version " + version);
    }
  }

  private final Supplier<FixedIndexed<Double>> doubleDictionarySupplier;

  private final Supplier<ColumnarDoubles> valueColumnSupplier;

  private final GenericIndexed<ImmutableBitmap> valueIndexes;

  private final BitmapFactory bitmapFactory;
  private final ImmutableBitmap nullValueBitmap;

  private ScalarDoubleColumnAndIndexSupplier(
      Supplier<FixedIndexed<Double>> longDictionary,
      Supplier<ColumnarDoubles> valueColumnSupplier,
      GenericIndexed<ImmutableBitmap> valueIndexes,
      BitmapFactory bitmapFactory
  )
  {
    this.doubleDictionarySupplier = longDictionary;
    this.valueColumnSupplier = valueColumnSupplier;
    this.valueIndexes = valueIndexes;
    this.bitmapFactory = bitmapFactory;
    this.nullValueBitmap = valueIndexes.get(0) == null ? bitmapFactory.makeEmptyImmutableBitmap() : valueIndexes.get(0);
  }

  @Override
  public NestedCommonFormatColumn get()
  {
    return new ScalarDoubleColumn(
        doubleDictionarySupplier.get(),
        valueColumnSupplier.get(),
        nullValueBitmap
    );
  }

  @Nullable
  @Override
  public <T> T as(Class<T> clazz)
  {
    if (clazz.equals(NullValueIndex.class)) {
      final BitmapColumnIndex nullIndex = new SimpleImmutableBitmapIndex(nullValueBitmap);
      return (T) (NullValueIndex) () -> nullIndex;
    } else if (clazz.equals(DictionaryEncodedStringValueIndex.class)
               || clazz.equals(DictionaryEncodedValueIndex.class)) {
      return (T) new DoubleDictionaryEncodedValueSetIndex();
    } else if (clazz.equals(StringValueSetIndex.class)) {
      return (T) new DoubleValueSetIndex();
    } else if (clazz.equals(NumericRangeIndex.class)) {
      return (T) new DoubleNumericRangeIndex();
    } else if (clazz.equals(DruidPredicateIndex.class)) {
      return (T) new DoublePredicateIndex();
    }

    return null;
  }

  private ImmutableBitmap getBitmap(int idx)
  {
    if (idx < 0) {
      return bitmapFactory.makeEmptyImmutableBitmap();
    }

    final ImmutableBitmap bitmap = valueIndexes.get(idx);
    return bitmap == null ? bitmapFactory.makeEmptyImmutableBitmap() : bitmap;
  }

  private class DoubleValueSetIndex implements StringValueSetIndex
  {
    @Override
    public BitmapColumnIndex forValue(@Nullable String value)
    {
      final boolean inputNull = value == null;
      final Double doubleValue = Strings.isNullOrEmpty(value) ? null : Doubles.tryParse(value);
      return new SimpleBitmapColumnIndex()
      {
        final FixedIndexed<Double> dictionary = doubleDictionarySupplier.get();

        @Override
        public double estimateSelectivity(int totalRows)
        {
          if (doubleValue == null) {
            if (inputNull) {
              return (double) getBitmap(0).size() / totalRows;
            } else {
              return 0.0;
            }
          }
          final int id = dictionary.indexOf(doubleValue);
          if (id < 0) {
            return 0.0;
          }
          return (double) getBitmap(id).size() / totalRows;
        }

        @Override
        public <T> T computeBitmapResult(BitmapResultFactory<T> bitmapResultFactory)
        {
          if (doubleValue == null) {
            if (inputNull) {
              return bitmapResultFactory.wrapDimensionValue(getBitmap(0));
            } else {
              return bitmapResultFactory.wrapDimensionValue(bitmapFactory.makeEmptyImmutableBitmap());
            }
          }
          final int id = dictionary.indexOf(doubleValue);
          if (id < 0) {
            return bitmapResultFactory.wrapDimensionValue(bitmapFactory.makeEmptyImmutableBitmap());
          }
          return bitmapResultFactory.wrapDimensionValue(getBitmap(id));
        }
      };
    }

    @Override
    public BitmapColumnIndex forSortedValues(SortedSet<String> values)
    {
      return new SimpleImmutableBitmapIterableIndex()
      {
        @Override
        public Iterable<ImmutableBitmap> getBitmapIterable()
        {
          DoubleSet doubles = new DoubleArraySet(values.size());
          boolean needNullCheck = false;
          for (String value : values) {
            if (value == null) {
              needNullCheck = true;
            } else {
              Double theValue = Doubles.tryParse(value);
              if (theValue != null) {
                doubles.add(theValue.doubleValue());
              }
            }
          }
          final boolean doNullCheck = needNullCheck;
          return () -> new Iterator<ImmutableBitmap>()
          {
            final FixedIndexed<Double> dictionary = doubleDictionarySupplier.get();
            final DoubleIterator iterator = doubles.iterator();
            int next = -1;
            boolean nullChecked = false;

            @Override
            public boolean hasNext()
            {
              if (doNullCheck && !nullChecked) {
                return true;
              }
              if (next < 0) {
                findNext();
              }
              return next >= 0;
            }

            @Override
            public ImmutableBitmap next()
            {
              if (doNullCheck && !nullChecked) {
                nullChecked = true;
                return getBitmap(0);
              }
              if (next < 0) {
                findNext();
                if (next < 0) {
                  throw new NoSuchElementException();
                }
              }
              final int swap = next;
              next = -1;
              return getBitmap(swap);
            }

            private void findNext()
            {
              while (next < 0 && iterator.hasNext()) {
                double nextValue = iterator.nextDouble();
                next = dictionary.indexOf(nextValue);
              }
            }
          };
        }
      };
    }
  }

  private class DoubleNumericRangeIndex implements NumericRangeIndex
  {
    @Override
    public BitmapColumnIndex forRange(
        @Nullable Number startValue,
        boolean startStrict,
        @Nullable Number endValue,
        boolean endStrict
    )
    {
      final FixedIndexed<Double> dictionary = doubleDictionarySupplier.get();
      IntIntPair range = dictionary.getRange(
          startValue == null ? null : startValue.doubleValue(),
          startStrict,
          endValue == null ? null : endValue.doubleValue(),
          endStrict
      );

      final int startIndex = range.leftInt();
      final int endIndex = range.rightInt();
      return new SimpleImmutableBitmapIterableIndex()
      {
        @Override
        public Iterable<ImmutableBitmap> getBitmapIterable()
        {
          return () -> new Iterator<ImmutableBitmap>()
          {
            final IntIterator rangeIterator = IntListUtils.fromTo(startIndex, endIndex).iterator();

            @Override
            public boolean hasNext()
            {
              return rangeIterator.hasNext();
            }

            @Override
            public ImmutableBitmap next()
            {
              return getBitmap(rangeIterator.nextInt());
            }
          };
        }
      };
    }
  }

  private class DoublePredicateIndex implements DruidPredicateIndex
  {
    @Override
    public BitmapColumnIndex forPredicate(DruidPredicateFactory matcherFactory)
    {
      return new SimpleImmutableBitmapIterableIndex()
      {
        @Override
        public Iterable<ImmutableBitmap> getBitmapIterable()
        {
          return () -> new Iterator<ImmutableBitmap>()
          {
            final Iterator<Double> iterator = doubleDictionarySupplier.get().iterator();
            final DruidDoublePredicate doublePredicate = matcherFactory.makeDoublePredicate();

            int next;
            int index = 0;
            boolean nextSet = false;

            @Override
            public boolean hasNext()
            {
              if (!nextSet) {
                findNext();
              }
              return nextSet;
            }

            @Override
            public ImmutableBitmap next()
            {
              if (!nextSet) {
                findNext();
                if (!nextSet) {
                  throw new NoSuchElementException();
                }
              }
              nextSet = false;
              return getBitmap(next);
            }

            private void findNext()
            {
              while (!nextSet && iterator.hasNext()) {
                Double nextValue = iterator.next();
                if (nextValue == null) {
                  nextSet = doublePredicate.applyNull();
                } else {
                  nextSet = doublePredicate.applyDouble(nextValue);
                }
                if (nextSet) {
                  next = index;
                }
                index++;
              }
            }
          };
        }
      };
    }
  }

  private class DoubleDictionaryEncodedValueSetIndex implements DictionaryEncodedStringValueIndex
  {
    private final FixedIndexed<Double> dictionary = doubleDictionarySupplier.get();

    @Override
    public ImmutableBitmap getBitmap(int idx)
    {
      return ScalarDoubleColumnAndIndexSupplier.this.getBitmap(idx);
    }

    @Override
    public int getCardinality()
    {
      return dictionary.size();
    }

    @Nullable
    @Override
    public String getValue(int index)
    {
      final Double value = dictionary.get(index);
      return value == null ? null : String.valueOf(value);
    }
  }
}
