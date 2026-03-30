while true; do
  find . -type d \( -path "./node_modules" -o -path "./.git" -o -path "./.shadow-cljs" \) -prune -o -print | \
  entr -d rsync -avz --exclude 'node_modules' --exclude '.git' --exclude '.shadow-cljs' . jaggar@192.168.3.111:/Users/jaggar/Code/Paradise/
done
