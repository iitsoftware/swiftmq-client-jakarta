/*
 * Copyright 2019 IIT Software GmbH
 *
 * IIT Software GmbH licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License. You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 */

package com.swiftmq.mgmt;

import com.swiftmq.tools.dump.Dumpable;
import com.swiftmq.util.SwiftUtilities;

import java.io.DataInput;
import java.io.DataOutput;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.util.stream.IntStream;

/**
 * A Property object is part of an Entity. It consists of a name and a value and
 * further meta-data like min/max etc.
 */
public class Property implements Dumpable {
    String name = null;
    Entity parent = null;
    String displayName = null;
    String description = null;
    Class type = null;
    boolean readOnly = false;
    boolean mandatory = false;
    boolean rebootRequired = false;
    boolean storable = true;
    Object value = null;
    Comparable minValue = null;
    Comparable maxValue = null;
    Object defaultValue = null;
    List possibleValues = null;
    List possibleValueDescriptions = null;
    transient PropertyChangeListener propertyChangeListener;
    transient List watchListeners = null;
    ReentrantReadWriteLock lock = new ReentrantReadWriteLock();

    /**
     * Creates a new Property.
     *
     * @param name property name.
     */
    public Property(String name) {
        this.name = name;
    }

    Property() {
    }

    /**
     * Converts a String into the given type.
     *
     * @param type the type.
     * @param v    the string value.
     * @return the converted object.
     * @throws InvalidTypeException on invalid type.
     */
    public static Object convertToType(Class type, String v) throws InvalidTypeException {
        Object r = null;
        try {
            if (type == String.class)
                r = v;
            else if (type == Boolean.class)
                r = Boolean.valueOf(v);
            else if (type == Double.class)
                r = Double.valueOf(v);
            else if (type == Integer.class)
                r = Integer.valueOf(v);
            else if (type == Long.class)
                r = Long.valueOf(v);
            else if (type == Float.class)
                r = Float.valueOf(v);
        } catch (NumberFormatException e) {
            throw new InvalidTypeException("invalid type for value; does not match " + type);
        }
        return r;
    }

    public int getDumpId() {
        return MgmtFactory.PROPERTY;
    }

    private void writeDump(DataOutput out, String s) throws IOException {
        if (s == null)
            out.writeByte(0);
        else {
            out.writeByte(1);
            out.writeUTF(s);
        }
    }

    private String readDump(DataInput in) throws IOException {
        byte set = in.readByte();
        if (set == 1)
            return in.readUTF();
        return null;
    }

    private void writeList(DataOutput out, List vl, Class type) throws IOException {
        if (vl == null)
            out.writeByte(0);
        else {
            out.writeByte(1);
            out.writeInt(vl.size());
            for (int i = 0; i < vl.size(); i++) {
                writeValue(out, vl.get(i), type);
            }
        }
    }

    private List readDump(DataInput in, Class type) throws IOException {
        byte set = in.readByte();
        if (set == 1) {
            int size = in.readInt();
            List vl = new ArrayList();
            for (int i = 0; i < size; i++) {
                vl.add(readValue(in, type));
            }
            return vl;
        }
        return null;
    }

    private void writeList(DataOutput out, List vl) throws IOException {
        if (vl == null)
            out.writeByte(0);
        else {
            out.writeByte(1);
            out.writeInt(vl.size());
            for (int i = 0; i < vl.size(); i++) {
                out.writeUTF((String) vl.get(i));
            }
        }
    }

    private List readList(DataInput in) throws IOException {
        byte set = in.readByte();
        if (set == 1) {
            int size = in.readInt();
            ArrayList vl = new ArrayList();
            for (int i = 0; i < size; i++) {
                vl.add(in.readUTF());
            }
            return vl;
        }
        return null;
    }

    private void writeValue(DataOutput out, Object v, Class type) throws IOException {
        if (v == null)
            out.writeByte(0);
        else {
            out.writeByte(1);
            if (type == String.class)
                out.writeUTF((String) v);
            else if (type == Boolean.class)
                out.writeBoolean(Boolean.parseBoolean(v.toString()));
            else if (type == Double.class)
                out.writeDouble(Double.parseDouble(v.toString()));
            else if (type == Integer.class)
                out.writeInt(Integer.parseInt(v.toString()));
            else if (type == Long.class)
                out.writeLong(Long.parseLong(v.toString()));
            else if (type == Float.class)
                out.writeFloat(Float.parseFloat(v.toString()));
        }
    }

    private Object readValue(DataInput in, Class type) throws IOException {
        Object v = null;
        byte set = in.readByte();
        if (set == 1) {
            if (type == String.class)
                v = in.readUTF();
            else if (type == Boolean.class)
                v = in.readBoolean();
            else if (type == Double.class)
                v = in.readDouble();
            else if (type == Integer.class)
                v = in.readInt();
            else if (type == Long.class)
                v = in.readLong();
            else if (type == Float.class)
                v = in.readFloat();
        }
        return v;
    }

    public void writeContent(DataOutput out)
            throws IOException {
        lock.readLock().lock();
        try {
            writeDump(out, name);
            writeDump(out, displayName);
            writeDump(out, description);
            writeDump(out, type.getName());
            out.writeBoolean(readOnly);
            out.writeBoolean(mandatory);
            out.writeBoolean(rebootRequired);
            out.writeBoolean(storable);
            writeValue(out, value, type);
            writeValue(out, minValue, type);
            writeValue(out, maxValue, type);
            writeValue(out, defaultValue, type);
            writeList(out, possibleValues, type);
            writeList(out, possibleValueDescriptions);
        } finally {
            lock.readLock().unlock();
        }

    }

    public void readContent(DataInput in)
            throws IOException {
        lock.writeLock().lock();
        try {
            name = readDump(in);
            displayName = readDump(in);
            description = readDump(in);
            try {
                type = Class.forName(readDump(in));
            } catch (ClassNotFoundException e) {
                throw new RuntimeException(e);
            }
            readOnly = in.readBoolean();
            mandatory = in.readBoolean();
            rebootRequired = in.readBoolean();
            storable = in.readBoolean();
            value = readValue(in, type);
            minValue = (Comparable) readValue(in, type);
            maxValue = (Comparable) readValue(in, type);
            defaultValue = readValue(in, type);
            possibleValues = readDump(in, type);
            possibleValueDescriptions = readList(in);
        } finally {
            lock.writeLock().unlock();
        }

    }

    /**
     * Returns the name.
     *
     * @return name.
     */
    public String getName() {
        lock.readLock().lock();
        try {
            return (name);
        } finally {
            lock.readLock().unlock();
        }

    }

    /**
     * Returns the display name.
     *
     * @return display name.
     */
    public String getDisplayName() {
        lock.readLock().lock();
        try {
            return (displayName);
        } finally {
            lock.readLock().unlock();
        }

    }

    /**
     * Set the display name (displayed in SwiftMQ Explorer).
     *
     * @param displayName display name.
     */
    public void setDisplayName(String displayName) {
        lock.writeLock().lock();
        try {
            this.displayName = displayName;
        } finally {
            lock.writeLock().unlock();
        }

    }

    /**
     * Returns the description.
     *
     * @return description.
     */
    public String getDescription() {
        lock.readLock().lock();
        try {
            return (description);
        } finally {
            lock.readLock().unlock();
        }

    }

    /**
     * Sets the description (displayed as tool tip in SwiftMQ Explorer).
     *
     * @param description description.
     */
    public void setDescription(String description) {
        lock.writeLock().lock();
        try {
            this.description = description;
        } finally {
            lock.writeLock().unlock();
        }

    }

    private boolean isInPossibleList(Object v) {
        if (possibleValues == null)
            return true;
        if (v == null)
            return false;
        return IntStream.range(0, possibleValues.size()).anyMatch(i -> ((Comparable) possibleValues.get(i)).compareTo(v) == 0);
    }

    /**
     * Returns the value.
     *
     * @return value.
     */
    public Object getValue() {
        lock.readLock().lock();
        try {
            if (value != null)
                return value;
            return defaultValue;
        } finally {
            lock.readLock().unlock();
        }

    }

    /**
     * Set the value.
     * This value must correspond to the type, specified for this Property.
     *
     * @param value the value.
     * @throws InvalidValueException   if the value doesn't match min/max/possibles.
     * @throws InvalidTypeException    if the value doesn't match the property type.
     * @throws PropertyChangeException thrown by a PropertyChangeListener.
     */
    public void setValue(Object value)
            throws InvalidValueException, InvalidTypeException, PropertyChangeException {
        PropertyChangeListener listener = null;
        lock.writeLock().lock();
        try {
            if (value != null) {
                if (type == null)
                    throw new InvalidTypeException("no type set");
                if (type != value.getClass())
                    throw new InvalidTypeException("invalid type for value; does not match " + type);
                if (minValue != null && minValue.compareTo(value) > 0)
                    throw new InvalidValueException("invalid value, must be >= " + minValue);
                if (maxValue != null && maxValue.compareTo(value) < 0)
                    throw new InvalidValueException("invalid value, must be <= " + maxValue);
                if (!isInPossibleList(value))
                    throw new InvalidValueException("invalid value, must be in " + possibleValues);
            } else {
                if (mandatory && defaultValue == null)
                    throw new InvalidValueException("Property is mandatory, value can't be null");
                if (type != String.class)
                    throw new InvalidValueException("Null values are only possible for String types");
            }
            this.value = value;
            listener = propertyChangeListener;
        } finally {
            lock.writeLock().unlock();
        }
        if (listener != null)
            listener.propertyChanged(this, this.value, value);
        notifyPropertyWatchListeners();
    }

    /**
     * Returns the type.
     *
     * @return type.
     */
    public Class getType() {
        lock.readLock().lock();
        try {
            return (type);
        } finally {
            lock.readLock().unlock();
        }

    }

    /**
     * Set the type of this Property.
     * Must be in Boolean, Double, Integer, Long, String, Float.
     *
     * @param type the type.
     * @throws InvalidTypeException if not Boolean, Double, Integer, Long, String, Float.
     */
    public void setType(Class type) throws InvalidTypeException {
        lock.writeLock().lock();
        try {
            if (type == null ||
                    type == Boolean.class ||
                    type == Double.class ||
                    type == Integer.class ||
                    type == Long.class ||
                    type == String.class ||
                    type == Float.class)
                this.type = type;
            else
                throw new InvalidTypeException("invalid type; must be of Boolean, Double, Integer, Long, Float, String");
        } finally {
            lock.writeLock().unlock();
        }

    }

    /**
     * Returns whether this Property is read-only.
     *
     * @return true/false.
     */
    public boolean isReadOnly() {
        lock.readLock().lock();
        try {
            return (readOnly);
        } finally {
            lock.readLock().unlock();
        }

    }

    /**
     * Set this Property read-only or not.
     * It cannot be changed through SwiftMQ Explorer/CLI when set to read-only.
     *
     * @param readOnly true/false.
     */
    public void setReadOnly(boolean readOnly) {
        lock.writeLock().lock();
        try {
            this.readOnly = readOnly;
        } finally {
            lock.writeLock().unlock();
        }

    }

    /**
     * Internal use only.
     */
    public boolean isStorable() {
        lock.readLock().lock();
        try {
            return (storable);
        } finally {
            lock.readLock().unlock();
        }

    }

    /**
     * Internal use only.
     */
    public void setStorable(boolean storable) {
        lock.writeLock().lock();
        try {
            this.storable = storable;
        } finally {
            lock.writeLock().unlock();
        }

    }

    /**
     * Returns whether a change of this Property requires a reboot of the router.
     *
     * @return true/false.
     */
    public boolean isRebootRequired() {
        lock.readLock().lock();
        try {
            return (rebootRequired);
        } finally {
            lock.readLock().unlock();
        }

    }

    /**
     * Set whether a change of this Property requires a reboot of the router.
     * If true, SwiftMQ Explorer/CLI display a resp. message and a PropertyChangeListener isn't required.
     *
     * @param rebootRequired true/false.
     */
    public void setRebootRequired(boolean rebootRequired) {
        lock.writeLock().lock();
        try {
            this.rebootRequired = rebootRequired;
        } finally {
            lock.writeLock().unlock();
        }

    }

    /**
     * Returns whether a value of this Property is mandatory.
     *
     * @return true/false.
     */
    public boolean isMandatory() {
        lock.readLock().lock();
        try {
            return (mandatory);
        } finally {
            lock.readLock().unlock();
        }

    }

    /**
     * Specified whether a value of this Property is mandatory.
     * In case of  true and when creating a new Entity with SwiftMQ Explorer/CLI,
     * these tools will check whether a value is set and display an error if the
     * value isn't specified by the user.
     *
     * @param mandatory true/false.
     */
    public void setMandatory(boolean mandatory) {
        lock.writeLock().lock();
        try {
            this.mandatory = mandatory;
        } finally {
            lock.writeLock().unlock();
        }

    }

    /**
     * Returns the minimum value.
     *
     * @return min value.
     */
    public Comparable getMinValue() {
        lock.readLock().lock();
        try {
            return (minValue);
        } finally {
            lock.readLock().unlock();
        }

    }

    /**
     * Set a minimum value for this Property.
     * If set, <code>setValue()</code> will always verify the input against this value.
     *
     * @param minValue min value.
     * @throws InvalidTypeException if a type is not set.
     */
    public void setMinValue(Comparable minValue)
            throws InvalidTypeException {
        lock.writeLock().lock();
        try {
            if (type == null)
                throw new InvalidTypeException("type not set");
            this.minValue = minValue;
        } finally {
            lock.writeLock().unlock();
        }

    }

    /**
     * Returns the maximum value.
     *
     * @return max value.
     */
    public Comparable getMaxValue() {
        lock.readLock().lock();
        try {
            return (maxValue);
        } finally {
            lock.readLock().unlock();
        }

    }

    /**
     * Set a maximum value for this Property.
     * If set, <code>setValue()</code> will always verify the input against this value.
     *
     * @param maxValue max value.
     * @throws InvalidTypeException if a type is not set.
     */
    public void setMaxValue(Comparable maxValue)
            throws InvalidTypeException {
        lock.writeLock().lock();
        try {
            if (type == null)
                throw new InvalidTypeException("type not set");
            this.maxValue = maxValue;
        } finally {
            lock.writeLock().unlock();
        }

    }

    /**
     * Returns the list of possible values.
     *
     * @return list of possible values.
     */
    public List getPossibleValues() {
        lock.readLock().lock();
        try {
            return possibleValues;
        } finally {
            lock.readLock().unlock();
        }

    }

    /**
     * Sets a list of possible value for String types.
     * If set, <code>setValue()</code> will always verify the input against this value.
     *
     * @param possibleValues list of possible values.
     * @throws InvalidTypeException if a type is not set or a type in the list doesn't match the Property type.
     */
    public void setPossibleValues(List possibleValues)
            throws InvalidTypeException {
        lock.writeLock().lock();
        try {
            if (type == null)
                throw new InvalidTypeException("type not set");
            if (possibleValues != null) {
                for (Object value : possibleValues) {
                    if (value != null && type != value.getClass())
                        throw new InvalidTypeException("invalid type for value '" + value + "'; does not match " + type);
                }
            }
            this.possibleValues = possibleValues;
        } finally {
            lock.writeLock().unlock();
        }

    }

    /**
     * Currently not used.
     *
     * @return list of descriptions.
     */
    public List getPossibleValueDescriptions() {
        lock.readLock().lock();
        try {
            return possibleValueDescriptions;
        } finally {
            lock.readLock().unlock();
        }

    }

    /**
     * Currently not used.
     * Intended to use for SwiftMQ Explorer to display these descriptions instead of the values itself.
     *
     * @param possibleValueDescriptions list of descriptions.
     */
    public void setPossibleValueDescriptions(List possibleValueDescriptions) {
        lock.writeLock().lock();
        try {
            this.possibleValueDescriptions = possibleValueDescriptions;
        } finally {
            lock.writeLock().unlock();
        }

    }

    /**
     * Returns the default value.
     *
     * @return default value.
     */
    public Object getDefaultValue() {
        lock.readLock().lock();
        try {
            return defaultValue;
        } finally {
            lock.readLock().unlock();
        }

    }

    /**
     * Set the default value for this Property.
     *
     * @param defaultValue default value.
     */
    public void setDefaultValue(Object defaultValue) {
        lock.writeLock().lock();
        try {
            this.defaultValue = defaultValue;
        } finally {
            lock.writeLock().unlock();
        }

    }

    /**
     * Set a default Property.
     * Internal use only.
     *
     * @param defaultProp default Property.
     */
    public void setDefaultProp(Property defaultProp) {
        lock.writeLock().lock();
        try {
            if (defaultProp != null) {
                defaultValue = defaultProp.getValue();
                defaultProp.addPropertyWatchListener(new PropertyWatchListener() {
                    public void propertyValueChanged(Property p) {
                        defaultValue = p.getValue();
                    }
                });
            }
        } finally {
            lock.writeLock().unlock();
        }

    }

    /**
     * Returns the PropertyChangeListener.
     *
     * @return the listener.
     */
    public PropertyChangeListener getPropertyChangeListener() {
        lock.readLock().lock();
        try {
            return propertyChangeListener;
        } finally {
            lock.readLock().unlock();
        }

    }

    /**
     * Set a PropertyChangeListener.
     * There can only be one of this listeners which is the owner of this Property.
     *
     * @param propertyChangeListener the listener.
     */
    public void setPropertyChangeListener(PropertyChangeListener propertyChangeListener) {
        lock.writeLock().lock();
        try {
            this.propertyChangeListener = propertyChangeListener;
        } finally {
            lock.writeLock().unlock();
        }

    }

    /**
     * Add a PropertyWatchListener.
     * There can be multiple of this listeners which are informed if the value changes.
     *
     * @param l the listener.
     */
    public void addPropertyWatchListener(PropertyWatchListener l) {
        lock.writeLock().lock();
        try {
            if (watchListeners == null)
                watchListeners = new ArrayList();
            watchListeners.add(l);
        } finally {
            lock.writeLock().unlock();
        }

    }


    /**
     * Removes a PropertyWatchListener.
     *
     * @param l the listener.
     */
    public void removePropertyWatchListener(PropertyWatchListener l) {
        lock.writeLock().lock();
        try {
            if (watchListeners != null)
                watchListeners.remove(l);
        } finally {
            lock.writeLock().unlock();
        }

    }

    private List copyOf(List in) {
        lock.readLock().lock();
        try {
            List out = new ArrayList();
            if (in != null)
                out.addAll(in);
            return out;
        } finally {
            lock.readLock().unlock();
        }

    }

    private void notifyPropertyWatchListeners() {
        List copy = copyOf(watchListeners);
        for (Object watchListener : copy) {
            PropertyWatchListener l = (PropertyWatchListener) watchListener;
            l.propertyValueChanged(this);
        }
    }

    /**
     * Returns the parent Entity.
     *
     * @return parent Entity.
     */
    public Entity getParent() {
        lock.readLock().lock();
        try {
            return (parent);
        } finally {
            lock.readLock().unlock();
        }

    }

    protected void setParent(Entity parent) {
        lock.writeLock().lock();
        try {
            this.parent = parent;
        } finally {
            lock.writeLock().unlock();
        }

    }

    /**
     * Creates a deep copy of this Property.
     *
     * @return copy.
     */
    public Property createCopy() {
        lock.readLock().lock();
        try {
            Property prop = new Property(name);
            prop.displayName = displayName;
            prop.description = description;
            prop.type = type;
            prop.readOnly = readOnly;
            prop.mandatory = mandatory;
            prop.rebootRequired = rebootRequired;
            prop.storable = storable;
            prop.value = value;
            prop.minValue = minValue;
            prop.maxValue = maxValue;
            prop.defaultValue = defaultValue;
            prop.possibleValues = possibleValues;
            prop.possibleValueDescriptions = possibleValueDescriptions;
            return prop;
        } finally {
            lock.readLock().unlock();
        }

    }

    private String escapeJsonControls(String s) {
        return SwiftUtilities.replace(s, "\\", "\\\\").replace("\"", "\\\"");
    }

    private String quote(String s) {
        return "\"" + escapeJsonControls(s) + "\"";
    }

    public String toJson() {
        lock.readLock().lock();
        try {
            StringBuffer s = new StringBuffer();
            s.append("{");
            s.append(quote("name")).append(": ");
            s.append(quote(name)).append(", ");
            s.append(quote("displayName")).append(": ");
            s.append(quote(displayName)).append(", ");
            s.append(quote("description")).append(": ");
            s.append(quote(description)).append(", ");
            s.append(quote("type")).append(": ");
            s.append(quote(type.getSimpleName())).append(", ");
            s.append(quote("readOnly")).append(": ");
            s.append(readOnly).append(", ");
            s.append(quote("mandatory")).append(": ");
            s.append(mandatory).append(", ");
            s.append(quote("rebootRequired")).append(": ");
            s.append(rebootRequired);
            if (value != null) {
                s.append(", ");
                s.append(quote("value")).append(": ");
                if (value instanceof String)
                    s.append(quote((String) value));
                else
                    s.append(value);
            }
            if (minValue != null) {
                s.append(", ");
                s.append(quote("minValue")).append(": ");
                s.append(minValue);
            }
            if (maxValue != null) {
                s.append(", ");
                s.append(quote("maxValue")).append(": ");
                s.append(maxValue);
            }
            if (defaultValue != null) {
                s.append(", ");
                s.append(quote("defaultValue")).append(": ");
                if (type == String.class)
                    s.append(quote((String) defaultValue));
                else
                    s.append(defaultValue);
            }
            if (possibleValues != null) {
                s.append(", ");
                s.append(quote("possibleValues")).append(": ");
                s.append("[");
                for (int i = 0; i < possibleValues.size(); i++) {
                    if (i > 0)
                        s.append(", ");
                    s.append(quote(possibleValues.get(i).toString()));
                }
                s.append("]");
            }
            s.append("}");
            return s.toString();
        } finally {
            lock.readLock().unlock();
        }

    }

    public String toString() {
        lock.readLock().lock();
        try {
            StringBuffer s = new StringBuffer();
            s.append("[Property, name=");
            s.append(name);
            s.append(", displayName=");
            s.append(displayName);
            s.append(", description=");
            s.append(description);
            s.append(", type=");
            s.append(type);
            s.append(", value=");
            s.append(value);
            s.append(", readOnly=");
            s.append(readOnly);
            s.append(", storable=");
            s.append(storable);
            s.append(", mandatory=");
            s.append(mandatory);
            s.append(", rebootRequired=");
            s.append(rebootRequired);
            s.append(", minValue=");
            s.append(minValue);
            s.append(", maxValue=");
            s.append(maxValue);
            s.append(", possibleValues=");
            s.append(possibleValues);
            s.append(", possibleValueDescriptions=");
            s.append(possibleValueDescriptions);
            s.append(", defaultValue=");
            s.append(defaultValue);
            s.append("]");
            return s.toString();
        } finally {
            lock.readLock().unlock();
        }

    }
}

