// Copyright 2011, 2012 Howard M. Lewis Ship
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
// http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.

package com.howardlewisship.tapx.core.components;

import com.howardlewisship.tapx.core.multiselect.MultipleSelectModel;
import org.apache.tapestry5.*;
import org.apache.tapestry5.annotations.*;
import org.apache.tapestry5.beaneditor.BeanModel;
import org.apache.tapestry5.corelib.components.BeanEditor;
import org.apache.tapestry5.corelib.components.Palette;
import org.apache.tapestry5.corelib.components.Zone;
import org.apache.tapestry5.func.F;
import org.apache.tapestry5.func.Flow;
import org.apache.tapestry5.func.Mapper;
import org.apache.tapestry5.internal.bindings.AbstractBinding;
import org.apache.tapestry5.ioc.Messages;
import org.apache.tapestry5.ioc.annotations.Inject;
import org.apache.tapestry5.json.JSONArray;
import org.apache.tapestry5.json.JSONObject;
import org.apache.tapestry5.plastic.PlasticUtils;
import org.apache.tapestry5.services.*;
import org.apache.tapestry5.services.javascript.JavaScriptSupport;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Both a simplification of the Tapestry {@link Palette} component, and an extension in that it supports
 * adding new values on the fly, using a form located inside a Modalbox modal dialog. Specifically
 * limited to editing a <em>Set</em> of values: element order is immaterial, and the UI keeps
 * the values sorted in alphabetical order by {@linkplain MultipleSelectModel#toLabel(Object) label},
 * or via an alternate property (defined by the sortProperty parameter).
 * <p/>
 * The UI includes an "Add" button to add a new value of the type appropriate to the set. This sets up a modal dialog on
 * the client side, and a uses a server-side {@link BeanEditor} to render the form. Informal blocks bound do this
 * component will, in turn, be provided to the BeanEditor component for use as property overrides.
 * <p/>
 * TODO: Rename this to SetEditor and delete the current SetEditor.
 */
@Import(stack = "tapx-core")
@SuppressWarnings("rawtypes")
@SupportsInformalParameters
@Events("configureNewValue: allows the container to configure a new value prior to it being saved")
public class MultipleSelect implements Field
{
    /**
     * The set of values edited by the component; this is used when rendering. When the form is submitted,
     * the set is modified, removing some old values and adding in some new values.
     */
    @Parameter(required = true, allowNull = false, autoconnect = true)
    private Set values;

    @Parameter(required = true, allowNull = false)
    private MultipleSelectModel model;

    /**
     * Used when creating a form to allow a new value to be created. If not supplied, a default model
     * is generated from the {@linkplain BeanModelSource#createEditModel(Class, org.apache.tapestry5.ioc.Messages) bean
     * model source} using the class of the {@linkplain MultipleSelectModel#createEmptyInstance() new instance}.
     */
    @Property
    @Parameter(allowNull = false)
    private BeanModel beanModel;

    /**
     * Additional CSS class(es), beyond the mandatory default of "tx-multiselect".
     * The CSS class(es) will also be used by the form used to gather data for a new field.
     */
    @Property
    @Parameter(name = "class", defaultPrefix = BindingConstants.LITERAL)
    private String className;

    /**
     * The user presentable label for the field. If not provided, a reasonable label is generated from the component's
     * id, first by looking for a message key named "id-label" (substituting the component's actual id), then by
     * converting the actual id to a presentable string (for example, "userId" to "User Id").
     */
    @Parameter(defaultPrefix = BindingConstants.LITERAL)
    private String label;

    @Property(write = false)
    @Parameter(defaultPrefix = BindingConstants.LITERAL, value = "message:tapx-multiple-select-selected-column-label")
    private String selectedColumnLabel;

    @Property(write = false)
    @Parameter(defaultPrefix = BindingConstants.LITERAL, value = "message:tapx-multiple-select-available-column-label")
    private String availableColumnLabel;

    /**
     * Property used when sorting the options. If null (the default), the sorting is based on the label.
     * The property should be a String property, or a numeric value. Numbers are converted to zero-padded strings.
     * Really only meant for positive numbers. Floats or decimals will cause a runtime exception.
     */
    @Parameter(defaultPrefix = BindingConstants.LITERAL)
    private String sortProperty;

    /**
     * Alternate label used to represent a "single" instance of the value; this is used as part of
     * button labels, and in the title of the modal dialog.
     */
    @Property
    @Parameter(defaultPrefix = BindingConstants.LITERAL, value = "prop:label")
    private String singularLabel;

    @Environmental
    private JavaScriptSupport jss;

    @Environmental
    private FormSupport formSupport;

    @Inject
    private Request request;

    @Inject
    private ComponentResources resources;

    @Inject
    private Messages messages;

    @Inject
    private ComponentDefaultProvider defaultProvider;

    private String clientId, controlName;

    /**
     * Parameter used to communicate a newValue instance created from the model up to the containing
     * component. This is used when creating UI blocks as overrides to the BeanEditor's default
     * UI blocks for the properties of the new instance.
     */
    @Property
    @Parameter
    private Object newValue;

    @InjectComponent
    private Zone newValueEditor;

    @Inject
    private Block editor, success;

    @Inject
    private BeanModelSource beanModelSource;

    @Inject

    private PropertyConduitSource propertyConduitSource;

    Object defaultBeanModel()
    {
        return new AbstractBinding()
        {
            @Override
            public boolean isInvariant()
            {
                return false;
            }

            public Object get()
            {
                Object instance =
                        newValue != null ? newValue : model.createEmptyInstance();

                return beanModelSource.createEditModel(instance.getClass(), resources.getContainerMessages());
            }
        };
    }

    private static class Option implements Comparable<Option>
    {
        final String label;

        final String clientValue;

        final String sortKey;

        public Option(String label, String clientValue, String sortKey)
        {
            this.label = label;
            this.clientValue = clientValue;
            this.sortKey = sortKey;
        }

        public int compareTo(Option o)
        {
            return this.sortKey.compareTo(o.sortKey);
        }
    }

    private final Mapper<Object, Option> toPair = new Mapper<Object, Option>()
    {
        public Option map(Object value)
        {

            String sortValue = toSortKey(value);

            return new Option(model.toLabel(value), model.toClient(value), sortValue);
        }
    };

    private String toSortKey(Object value)
    {

        if (sortProperty == null)
        {
            return model.toLabel(value);
        }


        PropertyConduit conduit = propertyConduitSource.create(value.getClass(), sortProperty);

        Class propertyType = conduit.getPropertyType();
        Object rawKey = conduit.get(value);

        if (propertyType == String.class)
        {
            return rawKey.toString();
        }

        if (Number.class.isInstance(rawKey))
        {
            // 19 digits is enough to encode a long value. That's overkill. In the future, we can use a StrategyRegistry
            // to adapt this to the type appropriately.

            return String.format("%019d", rawKey);
        }

        throw new IllegalArgumentException(String.format("Sort property '%s' of %s is type %s which can not be converted to a sort key.",
                sortProperty,
                PlasticUtils.toTypeName(value.getClass()),
                PlasticUtils.toTypeName(propertyType)));
    }

    public static class ProcessSubmission implements ComponentAction<MultipleSelect>
    {
        private static final long serialVersionUID = -7656903896600563715L;

        private final String name;

        public ProcessSubmission(String name)
        {
            this.name = name;
        }

        public void execute(MultipleSelect component)
        {
            component.processSubmission(name);
        }

        public String toString()
        {
            return String.format("ProcessSubmission[%s]", name);
        }
    }

    final String defaultLabel()
    {
        return defaultProvider.defaultLabel(resources);
    }

    public String getClientId()
    {
        return clientId;
    }

    public String getControlName()
    {
        return controlName;
    }

    public String getLabel()
    {
        return label;
    }

    /**
     * Always returns false.
     */
    public boolean isDisabled()
    {
        return false;
    }

    /**
     * Always returns false.
     */
    public boolean isRequired()
    {
        return false;
    }

    public String getComputedClassName()
    {
        return className == null ? "tx-multiselect" : "tx-multiselect " + className;
    }

    @SuppressWarnings("unchecked")
    void setupRender()
    {
        clientId = jss.allocateClientId(resources);

        controlName = formSupport.allocateControlName(resources.getId());

        formSupport.store(this, new ProcessSubmission(controlName));

        JSONObject spec = new JSONObject(
                "clientId", clientId,
                "newValueTitle", "New " + singularLabel,
                "newValueURL", createEventURL("newValue"));

        for (Object value : values)
        {
            String clientValue = model.toClient(value);

            spec.append("values", clientValue);
        }

        Flow<Object> valuesFlow = F.flow(model.getAvailableValues());

        for (Option option : valuesFlow.map(toPair).sort())
        {
            spec.append("model", new JSONArray(option.clientValue, option.label, option.sortKey));
        }

        jss.addInitializerCall("tapxMultipleSelect", spec);
    }

    private String createEventURL(String eventName)
    {
        return resources.createEventLink(eventName, clientId).toURI();
    }

    @SuppressWarnings("unchecked")
    protected void processSubmission(String name)
    {
        controlName = name;

        JSONArray selected = new JSONArray(request.getParameter(name));

        Set newValues = new HashSet(selected.length());

        // First the values that have a server-side id.

        for (String clientValue : toStrings(selected))
        {
            Object serverValue = model.toValue(clientValue);

            if (serverValue == null)
                throw new RuntimeException(String.format("Unable to convert client value '%s' to a server-side value.",
                        clientValue));

            newValues.add(serverValue);
        }

        // Keep just what's in newValues
        values.retainAll(newValues);

        // Now add in anything in newValues that wasn't in values
        values.addAll(newValues);
    }

    private List<String> toStrings(JSONArray values)
    {
        List<String> result = new ArrayList<String>(values.length());

        for (int i = 0; i < values.length(); i++)
        {
            result.add(values.getString(i));
        }

        return result;
    }

    /**
     * Event handler triggered from the client side. The result of this event is a partial page update response
     * that will be used to fill in the content of the modal dialog.
     */
    Object onNewValue(String clientId)
    {
        this.clientId = clientId;

        return editor;
    }

    /**
     * Event handler triggered when the modal dialog is submitted.
     */
    void onPrepareForSubmitFromNewValue(String clientId)
    {
        this.clientId = clientId;
    }

    /**
     * Event handler when preparing to render or submit the new value form; creates a new empty instance.
     */
    void onPrepareFromNewValue()
    {
        newValue = model.createEmptyInstance();
    }

    Object onSuccessFromNewValue()
    {
        resources.triggerEvent("configureNewValue", new Object[]{newValue}, null);

        // Save the new value to the database (or whatever it takes to assign a propery id to it).

        model.persistNewInstance(newValue);

        // Return a block that will render the content. The content will be empty,
        // but it gives us a chance to write the necessary JS at the correct time.

        return success;
    }

    void onWriteSuccessJavaScript()
    {
        JSONObject spec = new JSONObject("clientId", clientId,
                "clientValue", model.toClient(newValue),
                "label", model.toLabel(newValue),
                "sort", toSortKey(newValue));

        jss.addInitializerCall("tapxMultipleSelectNewValue", spec);
    }

    Object onFailureFromNewValue()
    {
        return newValueEditor.getBody();
    }

    public String getAddNewButtonLabel()
    {
        return messages.format("tapx-multiple-select-add-button-label", singularLabel);
    }
}
