(ns input.composer
    (:require [promesa.core :as p]
            [re-frame.core :as re-frame]
            [taoensso.timbre :as log]
            [clojure.string :as str]
            [reagent.core :as r]
            [utils.helpers :refer [mxc->url url->mxc]]
            [input.autocomplete :refer [user-mention-options emoji-suggestion-options]]
            ["react" :as react]
            ["@tiptap/react" :refer [useEditor EditorContent]]
            ["@tiptap/starter-kit" :default StarterKit]
            ["@tiptap/extension-placeholder" :default Placeholder]
            ["@tiptap/extension-mention" :default Mention]
            ["@tiptap/extension-link" :default Link]
            ["@tiptap/core" :refer [Extension Node mergeAttributes]]
            ["prosemirror-state" :refer [Plugin PluginKey]]
            ["generated-compat" :as sdk :refer [MessageType MessageFormat MediaSource UploadSource UploadParameters]]))

(defn renderHtml [^js props]
                      #js ["img" (mergeAttributes
                                  (.-HTMLAttributes props)
                                  #js {"data-emote" true
                                       "class" "chat-input-emote"
                                       "style" "height: 1.5em; width: auto; vertical-align: middle; display: inline-block;"})])

(defn prepare-html-for-editor [raw-html]
  (if (string? raw-html)
    (str/replace raw-html
                 #"mxc://[^\"\'\s>]+"
                 #(mxc->url %))
    ""))

(defn- get-matrix-formatted-body [editor]
  (let [json (.. editor getJSON -content)
        node->html (fn node->html [node]
                     (let [type  (.-type node)
                           attrs (.-attrs node)
                           marks (.-marks node)
                           base-content
                           (cond
                             (= type "customEmote")
                             (let [render-vec (renderHtml #js {:HTMLAttributes attrs})
                                   html-attrs (second render-vec)
                                   shortcode  (aget attrs "shortcode")
                                   mxc-uri    (url->mxc (aget attrs "src"))]
                               (str "<img data-mx-emoticon src=\"" mxc-uri "\""
                                    " alt=\":" shortcode ":\" title=\":" shortcode ":\""
                                    " style=\"" (aget html-attrs "style") "\">"))
                             (= type "text") (.-text node)
                             :else "")
                           with-marks
                           (if marks
                             (reduce (fn [html mark]
                                       (case (.-type mark)
                                         "link"   (str "<a href=\"" (.. mark -attrs -href) "\">" html "</a>")
                                         "bold"   (str "<strong>" html "</strong>")
                                         "italic" (str "<em>" html "</em>")
                                         "strike" (str "<del>" html "</del>")
                                         "code"   (str "<code>" html "</code>")
                                         html))
                                     base-content
                                     (js/Array.from marks))
                             base-content)]
                       with-marks))]
    (str/join ""
      (map (fn [p]
             (str "<p>"
                  (str/join "" (map node->html (js/Array.from (or (.-content p) #js []))))
                  "</p>"))
           (js/Array.from json)))))

(def custom-emote
  (.create Node
   #js {:name "customEmote"
        :inline true
        :group "inline"
        :selectable true
        :atom true
        :addAttributes (fn [] #js {:src #js {:default nil}
                                   :shortcode #js {:default nil}})
        :parseHTML (fn []
                     #js [#js {:tag "img[data-emote]"}
                          #js {:tag "img[data-mx-emoticon]"}
                          #js {:tag "img"
                               :getAttrs (fn [^js node]
                                           #js {:src (.getAttribute node "src")
                                                :shortcode (or (.getAttribute node "alt")
                                                               (.getAttribute node "title"))})}])
        :renderHTML renderHtml}))

(def file-drop-extension
  (.create Extension
    #js {:name "fileDropHandler"
         :addOptions (fn [] #js {:onFiles nil})
         :addProseMirrorPlugins
         (fn []
           (this-as this
             #js [(new Plugin
                       #js {:key (new PluginKey "fileDropHandler")
                            :props #js {:handleDOMEvents
                                        #js {:drop (fn [view event]
                                                     (let [dt (.-dataTransfer event)
                                                           files (when dt (.-files dt))]
                                                       (if (and files (pos? (.-length files)))
                                                         (do
                                                           (.preventDefault event)
                                                           (when-let [on-files (.. this -options -onFiles)]
                                                             (on-files files))
                                                           true)
                                                         false)))
                                             :paste (fn [view event]
                                                      (let [cd (.-clipboardData event)
                                                            files (when cd (.-files cd))]
                                                        (if (and files (pos? (.-length files)))
                                                          (do
                                                            (.preventDefault event)
                                                            (when-let [on-files (.. this -options -onFiles)]
                                                              (on-files files))
                                                            true)
                                                          false)))}}})]))}))

(def submit-extension
  (.create Extension
    #js {:name "submitExtension"
         :addOptions (fn [] #js {:onSend nil})
         :addKeyboardShortcuts
         (fn []
           (this-as this
             #js {"Enter" (fn [context]
                            (let [editor (.-editor context)
                                  text (.getText editor)
                                  html (.getHTML editor)
                                  on-send (.. this -options -onSend)]
                              (when (and on-send (not (str/blank? text)))
                                (on-send text html)
                                (.commands.clearContent editor true))
                              true))
                  "Shift-Enter" (fn [] false)}))}))

(defn tiptap-component [^js props]
  (let [active-id    (.. props -children -activeId)
        on-send      (.. props -children -onSend)
        on-files     (.. props -children -onFiles)
        on-change    (.. props -children -onChange)
        loaded-text  (.. props -children -loadedText)
        on-ready     (.. props -children -onEditorReady)
        editor (useEditor
                #js {:extensions #js [(.configure StarterKit #js {})
                                      (.configure Placeholder #js {:placeholder "Type a message..."})
                                      (.configure submit-extension #js {:onSend on-send})
                                      (.configure file-drop-extension #js {:onFiles on-files})
                                      (.configure custom-emote #js {})
                                      (.configure Mention #js {:name "emojiSuggestion"
                                                               :suggestion (emoji-suggestion-options)})
                                      (.configure Mention #js {:name "userMention"
                                                               :suggestion (user-mention-options)})
                                      (.configure Link #js {:openOnClick false
                                                            :autolink true
                                                            :linkOnPaste true})]
                     :content (or loaded-text "")
                     :editable (boolean active-id)
                     :onUpdate (fn [ctx]
                                 (when on-change
                                   (on-change (.getText (.-editor ctx))
                                              (.getHTML (.-editor ctx)))))
                     :onCreate (fn [ctx]
                                 (when on-ready
                                   (on-ready (.-editor ctx))))
                     :editorProps #js {:attributes #js {:class "tiptap-editor-surface"}}}
                #js [active-id (boolean loaded-text)])]
    (if-not editor
      (react/createElement "div" #js {:className "timeline-input-wrapper"}
                           (react/createElement "div" #js {:className "tiptap-editor-surface"}))
      (react/createElement "div"
                           #js {:key (str "editor-" active-id "-" (when (seq loaded-text) "ready"))
                                :className "timeline-input-wrapper"
                                :onClick (fn [] (.commands.focus editor))}
                           (react/createElement EditorContent #js {:editor editor})))))