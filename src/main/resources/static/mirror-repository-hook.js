define('et/hook/mirror', ['jquery', 'exports'], function ($, exports) {

    exports.init = function (createSubView, createButton) {
        $('#et-add-button').click(function () {
            try {
                var currIndex, index, name, html;

                // Determine the current index from the last password input
                name = $('div.hook-config-contents input:password').last().attr("name");

                if (name && name.length >= 8) {
                    currIndex = parseInt(name.substring(8));
                    index = (isNaN(currIndex) ? 0 : currIndex) + 1;
                } else {
                    index = '';
                }

                // Insert template after last field-group div
                html = createSubView({index: index, config: {}, errors: {}});
                $('#et-add-button').before(html);

                addRemoveButton();

            } catch (e) {
                alert(e.message);
            }
        });

        function addRemoveButton() {
            // Select all fieldset groups that don't have a remove button
            var group = $(".et-mirror-group").not(":has(.et-remove-button)");
            var html = createButton({name: 'et-remove-button', text: 'Remove', extraClasses: 'et-remove-button'});
            group.find('.et-mirror-repo input').after(html);

            group.find('.et-remove-button').click(function (e) {
                $(e.currentTarget).parents('.et-mirror-group').remove();
            })
        }

        addRemoveButton();
    }

});