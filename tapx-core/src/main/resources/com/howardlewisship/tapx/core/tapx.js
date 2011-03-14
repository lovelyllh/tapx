Tapx = {
	SetEditor : {

		updateHiddenField : function(field, state) {
			field.value = state.values.toJSON();
		},

		addItemToList : function(value, state, listElement, hiddenFieldElement) {
			var listItemElement = new Element("li")
					.update(Object.toHTML(value));

			var deleteElement = new Element("span", {
				"class" : "tx-seteditor-delete",
				title : "Delete"
			});

			deleteElement.observe("click", function(event) {
				event.stop();

				state.values = state.values.without(value);
				hiddenFieldElement.value = state.values.toJSON();

				listItemElement.remove();
			});

			listItemElement.insert(deleteElement);
			listElement.insert(listItemElement);
		}
	},

	Tree : {

		/** Approximate time per pixel for the hide and reveal animations. */
		ANIMATION_RATE : .005,

		MAX_ANIMATION_DURATION : .5,

		TOGGLE_TYPE : 'blind',

		QUEUE_NAME : 'tx-tree-updates',

		doAnimate : function(element) {
			var sublist = $(element).up('li').down("ul");

			var dim = sublist.getDimensions();

			var duration = Math.min(dim.height * this.ANIMATION_RATE,
					this.MAX_ANIMATION_DURATION)

			new Effect.toggle(sublist, this.TOGGLE_TYPE, {
				duration : duration,
				queue : {
					position : 'end',
					scope : this.QUEUE_NAME
				}
			});
		},

		animateRevealChildren : function(element) {
			$(element).addClassName("tx-tree-expanded");

			this.doAnimate(element);
		},

		animateHideChildren : function(element) {
			$(element).removeClassName("tx-tree-expanded");

			this.doAnimate(element);
		}
	}
};

Tapestry.Initializer.tapxSetEditor = function(spec) {

	/**
	 * A shared object that is mutable; adding or removing a value updates
	 * state.values
	 */
	var state = {
		values : $A(spec.values)
	};

	var textField = $(spec.id);

	var hiddenField = new Element("input", {
		type : "hidden",
		name : spec.name,
		value : state.values.toJSON()
	});

	textField.insert({
		after : hiddenField
	});

	var valuesColumn = textField.up(".tx-seteditor").down(".tx-valuescolumn");

	var valueList = new Element("ul");

	valuesColumn.insert(valueList);

	state.values.each(function(value) {
		Tapx.SetEditor.addItemToList(value, state, valueList, hiddenField);
	});

	textField.observe("keypress", function(event) {
		if (event.keyCode != Event.KEY_RETURN)
			return;

		event.stop();

		var value = textField.value;

		if (value == null || value.empty())
			return;

		Tapx.SetEditor.addItemToList(value, state, valueList, hiddenField);

		state.values.push(value);

		Tapx.SetEditor.updateHiddenField(hiddenField, state);

		textField.value = "";
	});
};

Tapestry.Initializer.tapxTreeNode = function(spec) {

	var loaded = false;
	var expanded = false;
	var loading = false;

	function successHandler(reply) {
		// Remove the Ajax load indicator
		$(spec.clientId).update("");
		$(spec.clientId).removeClassName("tx-empty-node");

		var response = reply.responseJSON;

		Tapestry.loadScriptsInReply(response, function() {
			var element = $(spec.clientId).up("li");
			var label = element.down("span.tx-tree-label");

			label.insert({
				after : response.content
			});

			// Hide the new sublist so that we can animate revealing it.
			element.down("ul").hide();

			Tapx.Tree.animateRevealChildren(spec.clientId);

			loading = false;
			loaded = true;
			expanded = true;
		});

	}

	function doLoad() {
		if (loading)
			return;

		loading = true;

		$(spec.clientId).addClassName("tx-empty-node");
		$(spec.clientId).update("<span class='tx-ajax-wait'/>");

		Tapestry.ajaxRequest(spec.expandChildrenURL, successHandler);
	}

	$(spec.clientId).observe(
			"click",
			function(event) {
				event.stop();

				if (!loaded) {

					doLoad();

					return;
				}

				// Children have been loaded, just a matter of toggling between
				// showing or hiding the children.

				var f = expanded ? Tapx.Tree.animateHideChildren
						: Tapx.Tree.animateRevealChildren;

				f.call(Tapx.Tree, spec.clientId);

				var url = expanded ? spec.markCollapsedURL
						: spec.markExpandedURL;

				Tapestry.ajaxRequest(url, {});

				expanded = !expanded;
			});
}

Tapestry.Initializer.tapxExpando = function(spec) {

	var loaded = false;

	function zone() {
		return Tapestry.findZoneManagerForZone(spec.zoneId);
	}

	function expand(event) {
		$(spec.clientId).removeClassName("tx-collapsed").addClassName(
				"tx-expanded");
		$(spec.clientId).down("div.tx-content").show();

		if (!loaded) {
			loaded = true;
			zone().updateFromURL(spec.contentURL);
		}
	}

	function collapse(event) {
		$(spec.clientId).removeClassName("tx-expanded").addClassName(
				"tx-collapsed");
		$(spec.clientId).down("div.tx-content").hide();
	}

	$(spec.clientId).down(".tx-expand").observe("click", expand);
	$(spec.clientId).down(".tx-collapse").observe("click", collapse);
};