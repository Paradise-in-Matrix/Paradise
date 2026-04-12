(ns input.composer
    (:require
     [clojure.string :as str]
     [utils.images :refer [mxc->url url->mxc mxc-image]]
     [input.autocomplete :refer [user-mention-options emoji-suggestion-options]]
     ["react" :as react]
     [reagent.core :as r]
     ["@tiptap/react" :refer [useEditor EditorContent ReactNodeViewRenderer NodeViewWrapper]]
     ["@tiptap/starter-kit" :default StarterKit]
     ["@tiptap/extension-placeholder" :default Placeholder]
     ["@tiptap/extension-mention" :default Mention]
     ["@tiptap/extension-link" :default Link]
     ["@tiptap/core" :refer [Extension Node mergeAttributes]]
      ["prosemirror-state" :refer [Plugin PluginKey]]))

(def emoji-trigger
  (-> Mention
      (.extend #js {:name "emojiTrigger"})
      (.configure #js {:suggestion (emoji-suggestion-options)})))

(def user-trigger
  (-> Mention
      (.extend #js {:name "userMention"
                    :renderHTML (fn [^js props]
                                  (let [node  (.-node props)
                                        id    (.. node -attrs -id)
                                        label (.. node -attrs -label)]
                                    #js ["a" #js {:href  (str "https://matrix.to/#/" id)
                                                  :class "user-mention"
                                                  :data-mx-user id}
                                         (str "@" label)]))})
      (.configure #js {:suggestion (user-mention-options)})))

(defn renderHtml [^js props]
  (let [attrs         (.-HTMLAttributes props)
        raw-shortcode (aget attrs "shortcode")
        display-code  (when raw-shortcode
                        (str ":" (str/replace (str raw-shortcode) #":" "") ":"))]
    #js ["img" (mergeAttributes
                attrs
                #js {"data-emote" true
                     "class"      "chat-input-emote"
                     "alt"        display-code
                     "shortcode"  display-code
                     "title"      display-code
                     "style"      "height: 1.5em; width: auto; vertical-align: middle; display: inline-block;"})]))

(defn prepare-html-for-editor [raw-html]
  (if (string? raw-html)
    (str/replace raw-html
                 #"mxc://[^\"\'\s>]+"
                 #(mxc->url %))
    ""))

(defn get-matrix-formatted-body [editor]
  (let [json (.. editor getJSON -content)
        node->html (fn node->html [node]
                     (let [type  (.-type node)
                           attrs (.-attrs node)
                           marks (.-marks node)
                           base-content
                           (cond
                             (= type "customEmote")
                             (let [shortcode  (aget attrs "shortcode")
                                   mxc-uri    (or (aget attrs "mxc") (url->mxc (aget attrs "src")))]
                               (str "<img data-mx-emoticon=\"\" src=\"" mxc-uri "\""
                                    " alt=\"" shortcode ":\" title=\"" shortcode ":\" height=\"32\">"))
                             (= type "userMention")
                             (let [id    (aget attrs "id")
                                   label (or (aget attrs "label") id)]
                               (str "<a href=\"https://matrix.to/#/" id "\">@" label "</a>"))
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


(defn emote-node-view [props]
  (let [node  (:node props)
        attrs (.-attrs node)
        mxc   (or (aget attrs "mxc") (aget attrs "src"))
        alt   (aget attrs "shortcode")]
    [:> NodeViewWrapper {:as "span" :style {:display "inline-block" :vertical-align "middle"}}
     [mxc-image
      {:mxc   mxc
       :class "chat-input-emote"
       :style {:height "1.5em" :width "auto"}
       :alt   alt}]]))

(def custom-emote
  (.create Node
   #js {:name "customEmote"
        :inline true
        :group "inline"
        :selectable true
        :atom true
        :addAttributes (fn [] #js {:src #js {:default nil}
                                   :shortcode #js {:default nil}})
        :addNodeView (fn []
                       (ReactNodeViewRenderer (r/reactify-component emote-node-view)))
        :renderText (fn [^js props]
              (let [shortcode (str (.. props -node -attrs -shortcode))]
                (if (and (seq shortcode)
                         (not (str/starts-with? shortcode ":")))
                  (str ":" shortcode ":")
                  (if (seq shortcode)
                    shortcode
                    ":emote:"))))
        :parseHTML (fn []
                     #js [#js {:tag "img[data-emote]"}
                          #js {:tag "img[data-mx-emoticon]"}
                          #js {:tag "img"
                               :getAttrs (fn [^js node]
                                           #js {:src (.getAttribute node "mxc")
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
                                                            (.focus view)
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
                                          (let [editor  (.-editor context)
                                                text    (.getText editor)
                                                html    (.getHTML editor)
                                                on-send (.. this -options -onSend)]
                                            (when (and on-send (on-send text html))
                                              (.commands.clearContent editor true))
                                            true))
                                "Shift-Enter" (fn [] false)}))}))

(defn tiptap-component [^js props]
  (let [active-id    (.. props -children -activeId)
        on-send      (.. props -children -onSend)
        on-files     (.. props -children -onFiles)
        on-change    (.. props -children -onChange)
        loaded-text  (.. props -children -loadedText)
        cached-html  (.. props -children -cachedHtml)
        placeholder  (.. props -children -placeholder)
        on-ready     (.. props -children -onEditorReady)
        latest-cbs   (react/useRef #js {:onSend on-send :onFiles on-files})
        _            (set! (.-current latest-cbs) #js {:onSend on-send :onFiles on-files})
        editor (useEditor
                #js {:extensions #js [(.configure StarterKit #js {})
                                      (.configure Placeholder #js {:placeholder placeholder})
                                      (.configure submit-extension
                                      #js {:onSend (fn [text html]
                                                     (let [cb (.-onSend (.-current latest-cbs))]
                                                       (when cb (cb text html))))})
                                      (.configure file-drop-extension
                                      #js {:onFiles (fn [files]
                                                      (let [cb (.-onFiles (.-current latest-cbs))]
                                                        (when cb (cb files))))})
                                      (.configure custom-emote #js {})
                                      emoji-trigger
                                      user-trigger
                                      (.configure Link #js {:openOnClick false
                                                            :autolink true
                                                            :linkOnPaste true})]
                     :content (or cached-html loaded-text "")
                     :editable (boolean active-id)
                     :onUpdate (fn [ctx]
                                 (when on-change
                                   (on-change (.getText (.-editor ctx))
                                              (.getHTML (.-editor ctx)))))
                     :onCreate (fn [ctx]
                                 (when on-ready
                                   (on-ready (.-editor ctx))))
                     :editorProps #js {:attributes #js {:class "tiptap-editor-surface"}}}
                #js [active-id])]

    (react/useEffect
     (fn []
       (when (and editor loaded-text)
         (let [current-html (.getHTML editor)]
           (when (not= current-html loaded-text)
             (.commands.setContent editor loaded-text))))
       js/undefined)
     #js [editor loaded-text])

    (if-not editor
      (react/createElement "div" #js {:className "timeline-input-wrapper"}
                           (react/createElement "div" #js {:className "tiptap-editor-surface"}))

      (react/createElement "div"
                           #js {:key (str "editor-surface-" active-id)
                                :className "timeline-input-wrapper"
                                :onClick (fn [] (.commands.focus editor))}
                           (react/createElement EditorContent #js {:editor editor})))))
